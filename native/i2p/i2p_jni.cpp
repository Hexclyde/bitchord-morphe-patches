// BitChord's JNI boundary to maintained PurpleI2P i2pd. No I2P wire protocol
// implementation lives here. One router lifecycle per private Android process.
#include <jni.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <filesystem>
#include <fstream>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#ifdef I2P_BOUNDARY_TEST
#include "tests/fake_i2p.h"
#else
#include "api.h"
#include "Destination.h"
#include "Streaming.h"
#include "RouterContext.h"
#endif

namespace {
using Clock = std::chrono::steady_clock;
using Stream = i2p::stream::Stream;
constexpr int MAX_IO = 65536, MAX_TIMEOUT = 120000;
constexpr size_t MAX_STREAMS = 16, MAX_ACCEPTED = 8;
struct Router;
struct StreamState {
    std::shared_ptr<Stream> stream;
    std::weak_ptr<Router> router;
    std::mutex reads, writes;
    std::atomic<bool> closed{false};
    void close() {
        if (!closed.exchange(true)) stream->AsyncClose();
    }
};
struct Router {
    std::atomic<bool> stopped{false};
    std::mutex mutex;
    std::condition_variable incoming;
    std::deque<std::shared_ptr<Stream>> accepted;
    std::shared_ptr<i2p::client::ClientDestination> destination;
    std::string address;
};
struct IoResult {
    std::mutex mutex;
    std::condition_variable changed;
    bool done = false;
    boost::system::error_code error;
    size_t size = 0;
    std::vector<uint8_t> bytes;
    explicit IoResult(size_t size) : bytes(size) {}
    void complete(const boost::system::error_code& e, size_t n) {
        std::lock_guard<std::mutex> lock(mutex);
        error = e; size = n; done = true; changed.notify_all();
    }
    bool wait(int milliseconds, const std::shared_ptr<StreamState>& state) {
        auto end = Clock::now() + std::chrono::milliseconds(milliseconds);
        std::unique_lock<std::mutex> lock(mutex);
        while (!done && !state->closed.load()) {
            auto now = Clock::now();
            if (now >= end) break;
            changed.wait_until(lock, std::min(end, now + std::chrono::milliseconds(100)));
        }
        return done;
    }
};
std::mutex registryMutex, lifecycleMutex;
std::shared_ptr<Router> current;
std::map<jlong, std::shared_ptr<StreamState>> streams;
jlong nextHandle = 2;
bool initializedOnce = false;

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
std::string stringValue(JNIEnv* env, jstring value) {
    if (!value) throw std::runtime_error("Missing I2P argument");
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    if (!bytes) throw std::runtime_error("Cannot read I2P argument");
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}
void timeoutCheck(int timeout) {
    if (timeout < 1 || timeout > MAX_TIMEOUT) throw std::runtime_error("I2P deadline must be 1..120000 ms");
}
void bufferCheck(JNIEnv* env, jbyteArray buffer, jint offset, jint length, jint timeout) {
    timeoutCheck(timeout);
    if (!buffer || offset < 0 || length < 0 || length > MAX_IO || offset > env->GetArrayLength(buffer) - length)
        throw std::runtime_error("Invalid I2P byte buffer (maximum 65536 bytes)");
}
std::shared_ptr<Router> routerFor(jlong handle, bool allowStopped = false) {
    std::lock_guard<std::mutex> lock(registryMutex);
    if (handle != 1 || !current || (!allowStopped && current->stopped.load()))
        throw std::runtime_error("I2P router is not running");
    return current;
}
std::shared_ptr<StreamState> streamFor(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = streams.find(handle);
    if (found == streams.end() || found->second->closed.load()) throw std::runtime_error("I2P stream is closed");
    return found->second;
}
jlong registerStream(const std::shared_ptr<Router>& router, const std::shared_ptr<Stream>& stream) {
    std::lock_guard<std::mutex> lock(registryMutex);
    if (router->stopped.load() || streams.size() >= MAX_STREAMS) {
        stream->AsyncClose();
        throw std::runtime_error("I2P stream capacity reached or router stopped");
    }
    auto state = std::make_shared<StreamState>();
    state->stream = stream; state->router = router;
    jlong handle = nextHandle++;
    streams.emplace(handle, state);
    return handle;
}
i2p::data::PrivateKeys loadKeys(const std::string& home) {
    auto filename = home + "/destination.keys";
    int fd = open(filename.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd >= 0) {
        struct stat info{};
        if (fstat(fd, &info) || !S_ISREG(info.st_mode) || info.st_size < 1 || info.st_size > 4096) {
            close(fd); throw std::runtime_error("Invalid I2P destination key file");
        }
        std::vector<uint8_t> bytes(static_cast<size_t>(info.st_size));
        size_t done = 0;
        while (done < bytes.size()) {
            ssize_t n = ::read(fd, bytes.data() + done, bytes.size() - done);
            if (n <= 0) { close(fd); throw std::runtime_error("Cannot read I2P destination keys"); }
            done += n;
        }
        close(fd);
        i2p::data::PrivateKeys keys;
        if (keys.FromBuffer(bytes.data(), bytes.size()) != bytes.size()) throw std::runtime_error("Corrupt I2P destination keys");
        return keys;
    }
    if (errno != ENOENT) throw std::runtime_error("Cannot open I2P destination keys");
    auto keys = i2p::data::PrivateKeys::CreateRandomKeys(i2p::data::SIGNING_KEY_TYPE_EDDSA_SHA512_ED25519,
        i2p::data::CRYPTO_KEY_TYPE_ECIES_X25519_AEAD, true);
    std::vector<uint8_t> bytes(keys.GetFullLen());
    keys.ToBuffer(bytes.data(), bytes.size());
    auto temporary = filename + ".pending-XXXXXX";
    fd = mkstemp(temporary.data());
    if (fd < 0) throw std::runtime_error("Cannot create I2P destination keys");
    fcntl(fd, F_SETFD, FD_CLOEXEC);
    bool ok = true;
    size_t done = 0;
    while (done < bytes.size()) {
        ssize_t n = ::write(fd, bytes.data() + done, bytes.size() - done);
        if (n <= 0) { ok = false; break; }
        done += n;
    }
    if (fsync(fd)) ok = false;
    close(fd);
    if (!ok || rename(temporary.c_str(), filename.c_str())) {
        unlink(temporary.c_str()); throw std::runtime_error("Cannot persist I2P destination keys");
    }
    return keys;
}
struct NullBuffer : std::streambuf { int overflow(int c) override { return c; } };
NullBuffer nullBuffer;
auto quietLog = std::make_shared<std::ostream>(&nullBuffer);
}

extern "C" JNIEXPORT jlong JNICALL Java_app_bitchord_soulseek_I2pNative_start(JNIEnv* env, jclass, jstring privateHome) {
    try {
        std::lock_guard<std::mutex> lifecycle(lifecycleMutex);
        if (initializedOnce) throw std::runtime_error("I2P requires a fresh private process after stop or startup failure");
        auto home = stringValue(env, privateHome);
        if (home.empty() || home[0] != '/' || home.size() > 2048 || home.find('\n') != std::string::npos)
            throw std::runtime_error("I2P home must be an absolute app-private path");
        struct stat info{};
        if (lstat(home.c_str(), &info) || !S_ISDIR(info.st_mode) || S_ISLNK(info.st_mode))
            throw std::runtime_error("I2P home must exist and cannot be a symbolic link");
        if (chmod(home.c_str(), 0700)) throw std::runtime_error("Cannot secure I2P home");
        if (!std::filesystem::is_directory(home + "/certificates/reseed"))
            throw std::runtime_error("Verified I2P reseed certificates were not installed");
        initializedOnce = true;
        std::vector<std::string> arguments = {"bitchord-i2p", "--datadir=" + home,
            "--certsdir=" + home + "/certificates", "--reseed.verify=true", "--notransit=true",
            "--share=0", "--bandwidth=256", "--ipv4=true", "--ipv6=false", "--floodfill=false",
            "--sam.enabled=false", "--http.enabled=false", "--httpproxy.enabled=false",
            "--socksproxy.enabled=false", "--bob.enabled=false", "--i2cp.enabled=false",
            "--i2pcontrol.enabled=false", "--upnp.enabled=false"};
        std::vector<char*> argv;
        for (auto& argument : arguments) argv.push_back(argument.data());
        i2p::api::InitI2P(static_cast<int>(argv.size()), argv.data(), "bitchord-i2p");
        // The lightweight upstream API deliberately omits daemon policy setup.
        // Set these on the core itself, not only in parsed command-line options.
        i2p::context.SetAcceptsTunnels(false);
        i2p::context.SetFloodfill(false);
        i2p::context.SetShareRatio(0);
        i2p::context.SetBandwidth(256);
        auto keys = loadKeys(home);
        auto router = std::make_shared<Router>();
        router->address = keys.GetPublic()->GetIdentHash().ToBase32() + ".b32.i2p";
        i2p::api::StartI2P(quietLog);
        i2p::util::Mapping options;
        options["inbound.length"] = "3"; options["outbound.length"] = "3";
        options["inbound.quantity"] = "2"; options["outbound.quantity"] = "2";
        options["i2p.streaming.profile"] = "1";
        options["i2p.streaming.maxConcurrentStreams"] = "16";
        router->destination = i2p::api::CreateLocalDestination(keys, true, &options);
        std::weak_ptr<Router> weak = router;
        auto acceptor = [weak](const std::shared_ptr<Stream>& stream) {
            auto owner = weak.lock();
            if (!owner || !stream) return;
            std::lock_guard<std::mutex> lock(owner->mutex);
            if (owner->stopped.load() || owner->accepted.size() >= MAX_ACCEPTED) { stream->AsyncClose(); return; }
            owner->accepted.push_back(stream); owner->incoming.notify_all();
        };
        auto local = router->destination;
        boost::asio::post(local->GetService(), [local, acceptor] { local->AcceptStreams(acceptor); });
        { std::lock_guard<std::mutex> lock(registryMutex); current = router; }
        return 1;
    } catch (const std::exception& error) { fail(env, error.what()); return 0; }
}

extern "C" JNIEXPORT jstring JNICALL Java_app_bitchord_soulseek_I2pNative_status(JNIEnv* env, jclass, jlong handle) {
    try {
        auto router = routerFor(handle, true);
        std::string status = "stopped";
        if (!router->stopped.load()) {
            auto promise = std::make_shared<std::promise<bool>>();
            auto result = promise->get_future();
            auto destination = router->destination;
            boost::asio::post(destination->GetService(), [promise, destination] { promise->set_value(destination->IsReady()); });
            status = result.wait_for(std::chrono::milliseconds(250)) == std::future_status::ready && result.get() ? "ready" : "starting";
        }
        return env->NewStringUTF(status.c_str());
    } catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL Java_app_bitchord_soulseek_I2pNative_localDestination(JNIEnv* env, jclass, jlong handle) {
    try { return env->NewStringUTF(routerFor(handle)->address.c_str()); }
    catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}
extern "C" JNIEXPORT jlong JNICALL Java_app_bitchord_soulseek_I2pNative_accept(JNIEnv* env, jclass, jlong handle, jint timeout) {
    try {
        timeoutCheck(timeout);
        auto router = routerFor(handle);
        std::unique_lock<std::mutex> lock(router->mutex);
        if (!router->incoming.wait_for(lock, std::chrono::milliseconds(timeout), [&] { return router->stopped.load() || !router->accepted.empty(); })) return 0;
        if (router->stopped.load()) throw std::runtime_error("I2P router stopped");
        auto stream = router->accepted.front(); router->accepted.pop_front();
        lock.unlock();
        return registerStream(router, stream);
    } catch (const std::exception& error) { fail(env, error.what()); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL Java_app_bitchord_soulseek_I2pNative_connect(JNIEnv* env, jclass, jlong handle, jstring destination, jint timeout) {
    try {
        timeoutCheck(timeout);
        auto router = routerFor(handle);
        auto address = stringValue(env, destination);
        if (address.size() != 60 || address.substr(52) != ".b32.i2p" || address.substr(0, 52).find_first_not_of("abcdefghijklmnopqrstuvwxyz234567") != std::string::npos)
            throw std::runtime_error("Only canonical I2P base32 destinations are supported");
        i2p::data::IdentHash identity;
        if (identity.FromBase32(address.substr(0, 52)) != 32 || identity.ToBase32() != address.substr(0, 52))
            throw std::runtime_error("Invalid or noncanonical I2P destination");
        struct Pending { std::mutex mutex; std::condition_variable changed; bool done = false, abandoned = false; std::shared_ptr<Stream> stream; };
        auto pending = std::make_shared<Pending>();
        auto local = router->destination;
        boost::asio::post(local->GetService(), [local, identity, pending] {
            local->CreateStream([pending](std::shared_ptr<Stream> stream) {
                std::lock_guard<std::mutex> lock(pending->mutex);
                if (pending->abandoned) { if (stream) stream->AsyncClose(); return; }
                pending->stream = stream; pending->done = true; pending->changed.notify_all();
            }, identity);
        });
        auto deadline = Clock::now() + std::chrono::milliseconds(timeout);
        std::unique_lock<std::mutex> lock(pending->mutex);
        while (!pending->done && !router->stopped.load() && Clock::now() < deadline)
            pending->changed.wait_until(lock, std::min(deadline, Clock::now() + std::chrono::milliseconds(100)));
        if (!pending->done || router->stopped.load()) {
            pending->abandoned = true;
            if (pending->stream) pending->stream->AsyncClose();
            throw std::runtime_error("I2P destination lookup deadline exceeded or router stopped");
        }
        auto stream = pending->stream;
        lock.unlock();
        if (!stream) throw std::runtime_error("I2P destination is unavailable");
        stream->Send(nullptr, 0); // i2pd streaming SYN; first read/write confirms peer reachability.
        return registerStream(router, stream);
    } catch (const std::exception& error) { fail(env, error.what()); return 0; }
}
extern "C" JNIEXPORT jint JNICALL Java_app_bitchord_soulseek_I2pNative_read(JNIEnv* env, jclass, jlong handle, jbyteArray buffer, jint offset, jint length, jint timeout) {
    try {
        bufferCheck(env, buffer, offset, length, timeout);
        auto state = streamFor(handle);
        std::lock_guard<std::mutex> serial(state->reads);
        if (!length) return 0;
        auto result = std::make_shared<IoResult>(length);
        state->stream->AsyncReceive(boost::asio::buffer(result->bytes), [result](const boost::system::error_code& error, size_t n) { result->complete(error, n); }, (timeout + 999) / 1000);
        if (!result->wait(timeout, state)) { state->close(); return 0; }
        if (result->error == boost::asio::error::timed_out) { state->close(); return 0; }
        if (result->error == boost::asio::error::eof) return -1;
        if (result->error) throw std::runtime_error("I2P stream read failed");
        if (result->size) env->SetByteArrayRegion(buffer, offset, static_cast<jsize>(result->size), reinterpret_cast<const jbyte*>(result->bytes.data()));
        return static_cast<jint>(result->size);
    } catch (const std::exception& error) { fail(env, error.what()); return -1; }
}
extern "C" JNIEXPORT jint JNICALL Java_app_bitchord_soulseek_I2pNative_write(JNIEnv* env, jclass, jlong handle, jbyteArray buffer, jint offset, jint length, jint timeout) {
    try {
        bufferCheck(env, buffer, offset, length, timeout);
        auto state = streamFor(handle);
        std::lock_guard<std::mutex> serial(state->writes);
        if (!length) return 0;
        auto result = std::make_shared<IoResult>(length);
        env->GetByteArrayRegion(buffer, offset, length, reinterpret_cast<jbyte*>(result->bytes.data()));
        if (env->ExceptionCheck()) return -1;
        state->stream->AsyncSend(result->bytes.data(), length, [result](const boost::system::error_code& error, size_t n) { result->complete(error, n); });
        if (!result->wait(timeout, state)) { state->close(); throw std::runtime_error("I2P stream write deadline exceeded"); }
        if (result->error || result->size != static_cast<size_t>(length)) { state->close(); throw std::runtime_error("I2P stream write failed"); }
        return static_cast<jint>(result->size);
    } catch (const std::exception& error) { fail(env, error.what()); return -1; }
}
extern "C" JNIEXPORT void JNICALL Java_app_bitchord_soulseek_I2pNative_closeStream(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<StreamState> state;
    { std::lock_guard<std::mutex> lock(registryMutex); auto found = streams.find(handle); if (found == streams.end()) return; state = found->second; streams.erase(found); }
    state->close();
}
extern "C" JNIEXPORT void JNICALL Java_app_bitchord_soulseek_I2pNative_stop(JNIEnv* env, jclass, jlong handle) {
    try {
        std::lock_guard<std::mutex> lifecycle(lifecycleMutex);
        auto router = routerFor(handle, true);
        if (router->stopped.exchange(true)) return;
        { std::lock_guard<std::mutex> lock(registryMutex); for (auto& item : streams) item.second->close(); streams.clear(); }
        { std::lock_guard<std::mutex> lock(router->mutex); for (auto& stream : router->accepted) stream->AsyncClose(); router->accepted.clear(); router->incoming.notify_all(); }
        i2p::api::DestroyLocalDestination(router->destination);
        i2p::api::StopI2P();
        i2p::api::TerminateI2P();
    } catch (const std::exception& error) { fail(env, error.what()); }
}
