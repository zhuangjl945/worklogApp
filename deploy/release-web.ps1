# ============================================================================
# 前端发布：构建 web/dist 并同步到 nginx 根目录
#
#   .\deploy\release-web.ps1 -Target "D:\srv\worklog\web"
#   .\deploy\release-web.ps1 -Target "\\fileserver\worklog$\web"   # 网络路径同理
#
# 只发前端：后端 jar 单独走正常发布，两者不需要同时停。
#
# 用 robocopy /MIR 而不是「先清空目录再拷贝」：清空会让这段时间进来的请求全部 502，
# /MIR 则是按文件比对同步，顺便把上一版残留的 hash 资源清掉（不然服务器上会堆几十个
# 永远不会再被引用的 index-XXXX.js）。
# 注意 robocopy 不是原子切换，真要零缝隙就用「dist 目录软链 + 切链」的做法。
#
param(
    [Parameter(Mandatory = $true)]
    [string]$Target,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$web = Join-Path $PSScriptRoot '..\web'
$dist = Join-Path $web 'dist'

Push-Location $web
try {
    if (-not $SkipInstall) {
        Write-Host '==> npm ci（锁定 package-lock，构建可复现）'
        npm ci
    }
    Write-Host '==> npm run build'
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "vite build 失败，退出码 $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (-not (Test-Path (Join-Path $dist 'index.html'))) {
    throw "构建产物里没找到 index.html：$dist"
}

Write-Host "==> 发布到 $Target"
# /MIR 镜像同步；/NFL /NDL /NJH 只是少刷屏；robocopy 退出码 0~7 都算成功，>=8 才是失败
robocopy $dist $Target /MIR /NFL /NDL /NJH /NP
if ($LASTEXITCODE -ge 8) { throw "robocopy 失败，退出码 $LASTEXITCODE" }
if ($LASTEXITCODE -ge 1) { Write-Host "   （robocopy 退出码 $LASTEXITCODE，属正常：有文件被复制/删除）" }

Write-Host '==> 完成。提醒：index.html 不能被缓存，改过 nginx 配置记得 nginx -t && nginx -s reload'
