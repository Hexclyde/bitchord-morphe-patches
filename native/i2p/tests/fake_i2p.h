// TEST ONLY. In-memory transport for exercising the production JNI boundary.
// Not a router, implementation of I2P, or evidence of public-overlay operation.
#pragma once
#include <boost/asio.hpp>
#include <algorithm>
#include <functional>
#include <thread>
namespace i2p {
namespace data {
constexpr int SIGNING_KEY_TYPE_EDDSA_SHA512_ED25519 = 7, CRYPTO_KEY_TYPE_ECIES_X25519_AEAD = 4;
struct IdentHash {
    std::string value = std::string(52, 'a');
    std::string ToBase32() const { return value; }
    size_t FromBase32(const std::string& s) {
        value = s;
        // Match the maintained codec's canonical padding for 32-byte identities.
        static const std::string alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        value.back() = alphabet[(alphabet.find(value.back()) & 16)];
        return 32;
    }
};
struct Identity { IdentHash hash; const IdentHash& GetIdentHash() const { return hash; } };
struct PrivateKeys {
    std::shared_ptr<Identity> identity = std::make_shared<Identity>();
    static PrivateKeys CreateRandomKeys(int, int, bool) { return {}; }
    std::shared_ptr<Identity> GetPublic() const { return identity; }
    size_t GetFullLen() const { return 64; }
    size_t ToBuffer(uint8_t* bytes, size_t size) const { std::fill_n(bytes, size, 0x42); return size; }
    size_t FromBuffer(const uint8_t* bytes, size_t size) { return size == 64 && bytes[0] == 0x42 ? size : 0; }
};
}
namespace util { using Mapping = std::map<std::string, std::string>; }
namespace stream {
struct Stream : std::enable_shared_from_this<Stream> {
    boost::asio::io_context& service;
    std::weak_ptr<Stream> peer;
    std::deque<uint8_t> incoming;
    boost::asio::mutable_buffer buffer;
    std::function<void(const boost::system::error_code&, size_t)> receive;
    bool closed = false;
    explicit Stream(boost::asio::io_context& context) : service(context) {}
    void deliver() {
        if (!receive || (incoming.empty() && !closed)) return;
        size_t n = std::min(buffer.size(), incoming.size());
        auto output = static_cast<uint8_t*>(buffer.data());
        for (size_t index = 0; index < n; index++) { output[index] = incoming.front(); incoming.pop_front(); }
        auto handler = std::move(receive);
        receive = {};
        handler(n ? boost::system::error_code{} : boost::asio::error::eof, n);
    }
    void Send(const uint8_t*, size_t) {}
    template<class Buffer, class Handler> void AsyncReceive(const Buffer& b, Handler&& handler, int) {
        boost::asio::post(service, [s = shared_from_this(), b, handler = std::move(handler)]() mutable {
            s->buffer = b; s->receive = std::move(handler); s->deliver();
        });
    }
    template<class Handler> void AsyncSend(const uint8_t* bytes, size_t size, Handler&& handler) {
        std::vector<uint8_t> copy(bytes, bytes + size);
        boost::asio::post(service, [s = shared_from_this(), copy, handler = std::move(handler)]() mutable {
            auto p = s->peer.lock();
            if (!p || s->closed || p->closed) { handler(boost::asio::error::broken_pipe, 0); return; }
            p->incoming.insert(p->incoming.end(), copy.begin(), copy.end()); p->deliver();
            handler({}, copy.size());
        });
    }
    void AsyncClose() {
        boost::asio::post(service, [s = shared_from_this()] {
            s->closed = true; s->deliver();
            auto p = s->peer.lock(); if (p) { p->closed = true; p->deliver(); }
        });
    }
};
}
namespace client {
struct ClientDestination {
    boost::asio::io_context service;
    boost::asio::executor_work_guard<boost::asio::io_context::executor_type> work{service.get_executor()};
    std::thread thread{[this] { service.run(); }};
    std::function<void(const std::shared_ptr<stream::Stream>&)> acceptor;
    std::vector<std::shared_ptr<stream::Stream>> retained;
    bool IsReady() const { return true; }
    auto& GetService() { return service; }
    template<class Handler> void AcceptStreams(Handler handler) { acceptor = handler; }
    template<class Handler> void CreateStream(Handler handler, const data::IdentHash& identity) {
        if (identity.value != std::string(52, 'a')) return; // simulate unresolved destination
        auto a = std::make_shared<stream::Stream>(service), b = std::make_shared<stream::Stream>(service);
        a->peer = b; b->peer = a; retained.push_back(a); retained.push_back(b);
        if (acceptor) acceptor(b);
        handler(a);
    }
    void Stop() { work.reset(); service.stop(); if (thread.joinable()) thread.join(); }
    ~ClientDestination() { Stop(); }
};
}
namespace api {
inline void InitI2P(int, char**, const char*) {}
inline void StartI2P(std::shared_ptr<std::ostream>) {}
inline void StopI2P() {}
inline void TerminateI2P() {}
inline auto CreateLocalDestination(const data::PrivateKeys&, bool, const util::Mapping*) { return std::make_shared<client::ClientDestination>(); }
inline void DestroyLocalDestination(std::shared_ptr<client::ClientDestination> destination) { destination->Stop(); }
}
struct Context { void SetAcceptsTunnels(bool) {} void SetFloodfill(bool) {} void SetShareRatio(int) {} void SetBandwidth(int) {} };
inline Context context;
}
