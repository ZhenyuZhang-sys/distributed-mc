// 第 9 步 9.1：没装 mod 的原版客户端（协议层，mineflayer）进服，收到区块，过一会儿退出。
// 输出一行 JSON：{"spawned":true,"chunks":N,"kicked":null,"error":null}。进不了服、被踢、出错时退出码 1。
// 用法：node join.js <host> <port> <玩家名> [停留秒数]
const mineflayer = require('mineflayer');

const [host, port, username, staySeconds = '10'] = process.argv.slice(2);
const result = { spawned: false, chunks: 0, kicked: null, error: null };
let done = false;

function finish(code) {
  if (done) return;
  done = true;
  console.log(JSON.stringify(result));
  process.exit(code);
}

const bot = mineflayer.createBot({ host, port: Number(port), username, version: '1.21.1', auth: 'offline' });
bot.on('chunkColumnLoad', () => { result.chunks++; });
bot.once('spawn', () => {
  result.spawned = true;
  setTimeout(() => { bot.quit(); finish(result.chunks > 0 ? 0 : 1); }, Number(staySeconds) * 1000);
});
bot.on('kicked', (reason) => { result.kicked = String(reason); finish(1); });
bot.on('error', (err) => { result.error = String(err); finish(1); });
bot.on('end', () => finish(result.spawned && result.chunks > 0 ? 0 : 1));
setTimeout(() => { result.error = 'timeout'; finish(1); }, 120_000);
