import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import os from 'node:os'

function lanIPv4s() {
  const found = []
  for (const list of Object.values(os.networkInterfaces())) {
    for (const net of list || []) {
      const v4 = net.family === 'IPv4' || net.family === 4
      if (!v4 || net.internal) continue
      if (net.address.startsWith('169.254.')) continue
      found.push(net.address)
    }

  }
  const rank = (ip) => {
    if (ip.startsWith('192.168.')) return 0
    if (ip.startsWith('10.')) return 1
    if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(ip)) return 2
    return 3
  }
  return [...new Set(found)].sort((a, b) => rank(a) - rank(b) || a.localeCompare(b))
}

/**
 * 开发态给渠道二维码用：浏览器打开的是 localhost 时，
 * 码里必须写成电脑的局域网 IP，否则手机扫开的是手机自己。
 */
function devPublicOriginPlugin() {
  return {
    name: 'dev-public-origin',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const path = req.url?.split('?')[0]
        if (path !== '/__dev/public-origin') {
          next()
          return
        }
        const addr = server.httpServer?.address()
        const port = typeof addr === 'object' && addr ? addr.port : server.config.server.port || 5173
        const origins = lanIPv4s().map((ip) => `http://${ip}:${port}`)
        res.setHeader('Content-Type', 'application/json; charset=utf-8')
        res.end(JSON.stringify({ origins }))
      })
    }
  }
}

export default defineConfig({
  plugins: [vue(), devPublicOriginPlugin()],
  server: {
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  preview: {
    host: true
  }
})
