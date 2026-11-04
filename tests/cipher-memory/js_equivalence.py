#!/usr/bin/env python3
"""Check InnerTubeX EJS wrapper whitespace and escaped payloads."""

import hashlib
import json
import re
import shutil
import subprocess

# MetrolistGroup/innertubex v0.7.0, EjsChallengeSolver.kt and QuickJsEngine.kt.
EJS = r"""
            (function() {
              var payload = @@PREFIX@@@@PLAYER@@@@SUFFIX@@;
              var r = jsc(payload);
              if (!r) return JSON.stringify({"type":"error","error":"jsc returned null"});
              if (r.type === "error")
                return JSON.stringify({"type":"error","error":String(r.error != null ? r.error : "")});
              if (r.type !== "result")
                return JSON.stringify({"type":"error","error":"unexpected jsc type: "+String(r.type)});
              var out = {"type":"result","responses":[]};
              if (typeof r.preprocessed_player === "string" && r.preprocessed_player.length > 0) {
                if (r.preprocessed_player.length > 8388608)
                  return JSON.stringify({"type":"error","error":"preprocessed player too large"});
                out.preprocessed_player = r.preprocessed_player;
              }
              var resps = r.responses;
              if (!resps) return JSON.stringify(out);
              for (var i = 0; i < resps.length; i++) {
                var resp = resps[i];
                if (!resp) {
                  out.responses.push({"type":"error","error":"null response"});
                  continue;
                }
                if (resp.type === "error") {
                  out.responses.push({
                    "type":"error",
                    "error":String(resp.error != null ? resp.error : "")
                  });
                } else if (resp.type === "result") {
                  var d = resp.data;
                  var plain = {};
                  if (d && typeof d === "object") {
                    var keys = Object.keys(d);
                    for (var j = 0; j < keys.length; j++) {
                      var k = keys[j];
                      var v = d[k];
                      var text = v == null ? "" : String(v);
                      if (text.length > 262144) {
                        plain = null;
                        break;
                      }
                      plain[k] = text;
                    }
                  }
                  if (plain == null)
                    out.responses.push({"type":"error","error":"solver output too large"});
                  else
                    out.responses.push({"type":"result","data":plain});
                } else {
                  out.responses.push({"type":"error","error":"unknown response type"});
                }
              }
              return JSON.stringify(out);
            })()
            """
BOUNDED = r"""
                    (function() {
                      const value = (@@CODE@@);
                      if (value == null) return "";
                      const text = String(value);
                      return text.length <= @@LIMIT@@ ? text : "";
                    })()
                    """


def substitute(template, **values):
    return re.sub(r"@@([A-Z]+)@@", lambda match: values[match[1]], template)


def trim_template(value):
    lines = re.split(r"\r\n|\n|\r", value)
    indent = min(len(line) - len(line.lstrip()) for line in lines if line.strip())
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(line[indent:] for line in lines)


def literal(value):
    escaped = []
    for character in value:
        escaped.append(
            {
                "\\": "\\\\",
                '"': '\\"',
                "\n": "\\n",
                "\r": "\\r",
                "\t": "\\t",
            }.get(character)
            or (f"\\u{ord(character):04x}" if ord(character) < 0x20 else character)
        )
    return '"' + "".join(escaped) + '"'


def scripts(player, requests, preprocessed, limit):
    prefix = (
        '{"type":"preprocessed","preprocessed_player":'
        if preprocessed
        else '{"type":"player","player":'
    )
    suffix = (
        (',"requests":' if preprocessed else ',"output_preprocessed":true,"requests":')
        + json.dumps(requests, ensure_ascii=False, separators=(",", ":"))
        + "}"
    )
    inner = substitute(EJS, PREFIX=prefix, PLAYER=literal(player), SUFFIX=suffix)
    return [
        (
            substitute(
                BOUNDED,
                CODE=trim_template(inner) if trim_inner else inner,
                LIMIT=str(limit),
            )
            if not trim_outer
            else trim_template(
                substitute(
                    BOUNDED,
                    CODE=trim_template(inner) if trim_inner else inner,
                    LIMIT=str(limit),
                )
            )
        )
        for trim_inner, trim_outer in (
            (True, True),
            (False, True),
            (True, False),
            (False, False),
        )
    ]


NODE = r"""
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const vm = require('node:vm');
const input = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));
const digest = value => crypto.createHash('sha256').update(value).digest('hex');
let checks = 0;
for (const test of input) {
  let baseline;
  for (const script of test.scripts) {
    let captured, calls = 0;
    const context = {jsc(payload) {
      captured = payload;
      calls++;
      if (test.mode === 'null') return null;
      if (test.mode === 'error') return {type:'error',error:'rejected'};
      if (test.mode === 'unexpected') return {type:'other'};
      if (test.mode === 'large-player') return {type:'result',preprocessed_player:'x'.repeat(8388609)};
      if (test.mode === 'large-output') return {type:'result',responses:[{type:'result',data:{x:'x'.repeat(262145)}}]};
      return {type:'result',preprocessed_player:'cached\\player\n雪',responses:[
        {type:'result',data:{answer:'solved\\value\n🎵',nullable:null}},
        {type:'error',error:'rejected'},null,{type:'other'}
      ]};
    }};
    const result = vm.runInNewContext(script, context, {timeout:5000});
    assert.equal(calls, 1);
    assert.equal(digest(JSON.stringify(captured)), test.payload_sha256);
    assert.equal(digest(captured.player ?? captured.preprocessed_player), test.player_sha256);
    assert.equal(result, test.expected);
    if (baseline !== undefined) assert.equal(result, baseline);
    baseline = result;
    checks += 4;
  }
}
console.log(`PASS: ${input.length} cipher payload cases, ${checks} equivalence assertions`);
"""


def main():
    node = shutil.which("node")
    if node is None:
        raise SystemExit("Node.js is required for cipher payload equivalence checks")
    ordinary = {
        "type": "result",
        "responses": [
            {"type": "result", "data": {"answer": "solved\\value\n🎵", "nullable": ""}},
            {"type": "error", "error": "rejected"},
            {"type": "error", "error": "null response"},
            {"type": "error", "error": "unknown response type"},
        ],
        "preprocessed_player": "cached\\player\n雪",
    }
    expected = {
        "normal": ordinary,
        "null": {"type": "error", "error": "jsc returned null"},
        "error": {"type": "error", "error": "rejected"},
        "unexpected": {"type": "error", "error": "unexpected jsc type: other"},
        "large-player": {"type": "error", "error": "preprocessed player too large"},
        "large-output": {
            "type": "result",
            "responses": [{"type": "error", "error": "solver output too large"}],
        },
    }
    payloads = [
        "",
        'var x = `  ${literal}  `;\n\t// \\"line\\"\r\n雪 🎵\u2028\u2029\0\x01\x1f',
        "@@PREFIX@@ @@PLAYER@@ @@SUFFIX@@ @@CODE@@ @@LIMIT@@",
        '\\"\n\r\t`' * 262144,
    ]
    requests = [
        {"type": "sig", "challenges": ["a\\b\nc", "雪🎵", '"` ${value}']},
        {"type": "n", "challenges": ["x\r\t\0y", "@@LIMIT@@"]},
    ]
    tests = []
    for player in payloads:
        for preprocessed in (False, True):
            modes = {"normal": ordinary} if len(player) > 100000 else expected
            limits = (10485760,) if len(player) > 100000 else (10485760, 1)
            for mode, output in modes.items():
                for limit in limits:
                    payload = {
                        "type": "preprocessed" if preprocessed else "player",
                        "preprocessed_player" if preprocessed else "player": player,
                    }
                    if not preprocessed:
                        payload["output_preprocessed"] = True
                    payload["requests"] = requests
                    # Object property order is immaterial to the solver's JSON output.
                    text = json.dumps(output, ensure_ascii=False, separators=(",", ":"))
                    tests.append(
                        {
                            "scripts": scripts(player, requests, preprocessed, limit),
                            "payload_sha256": hashlib.sha256(
                                json.dumps(
                                    payload, ensure_ascii=False, separators=(",", ":")
                                ).encode()
                            ).hexdigest(),
                            "player_sha256": hashlib.sha256(
                                player.encode()
                            ).hexdigest(),
                            "mode": mode,
                            "expected": (
                                text
                                if len(text.encode("utf-16-le")) // 2 <= limit
                                else ""
                            ),
                        }
                    )
    subprocess.run(
        [node, "-e", NODE],
        input=json.dumps(tests, ensure_ascii=False),
        text=True,
        check=True,
    )


if __name__ == "__main__":
    main()
