#!/usr/bin/env node
'use strict';

/**
 * 发布 release APK 到本地目录（不上传 git）
 *
 * 用法:
 *   node publish.js              # 使用 app/build.gradle.kts 中的 versionName
 *   node publish.js 1.0.6        # 校验/写入版本后编译并复制
 *   node publish.js 1.0.6 --force
 *
 * 产物:
 *   release/reader{version}.apk
 *
 * 说明:
 *   - release/ 已在 .gitignore，不会进仓库
 *   - 不执行 git add / commit / push / tag
 *   - 依赖项目根目录 keystore.properties + release.keystore 签名
 */

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const IS_WIN = process.platform === 'win32';
const GRADLE_WRAPPER = path.join(ROOT, IS_WIN ? 'gradlew.bat' : 'gradlew');
const APP_BUILD_GRADLE = path.join(ROOT, 'app', 'build.gradle.kts');
const RELEASE_DIR = path.join(ROOT, 'release');

function die(msg) {
  console.error(msg);
  process.exit(1);
}

function run(command, args) {
  const r = spawnSync(command, args, {
    cwd: ROOT,
    stdio: 'inherit',
    shell: IS_WIN,
  });
  if (r.error) {
    die(`执行失败: ${command} ${args.join(' ')}\n${r.error.message}`);
  }
  if (r.status !== 0) {
    process.exit(r.status ?? 1);
  }
}

function readGradleText() {
  if (!fs.existsSync(APP_BUILD_GRADLE)) {
    die(`未找到: ${APP_BUILD_GRADLE}`);
  }
  return fs.readFileSync(APP_BUILD_GRADLE, 'utf8');
}

function getVersionName(text) {
  const m = text.match(/versionName\s*=\s*["']([^"']+)["']/);
  return m ? m[1] : null;
}

function getVersionCode(text) {
  const m = text.match(/versionCode\s*=\s*(\d+)/);
  return m ? parseInt(m[1], 10) : null;
}

/**
 * 若目标版本与 gradle 不一致，写入 versionName；
 * versionCode 仅在 versionName 变更时 +1（避免重复发布同版本时无意义 bump）。
 */
function ensureVersion(targetVersion) {
  let text = readGradleText();
  const curName = getVersionName(text);
  const curCode = getVersionCode(text);
  if (!curName) {
    die('无法从 app/build.gradle.kts 读取 versionName');
  }
  if (!targetVersion || targetVersion === curName) {
    console.log(`[publish] versionName=${curName} versionCode=${curCode ?? '?'}`);
    return curName;
  }
  if (!/^\d+\.\d+\.\d+([.-].+)?$/.test(targetVersion)) {
    die(`版本号格式无效: ${targetVersion}（期望如 1.0.6）`);
  }
  const nextCode = (curCode != null ? curCode : 0) + 1;
  text = text.replace(
    /versionName\s*=\s*["'][^"']+["']/,
    `versionName = "${targetVersion}"`,
  );
  if (curCode != null) {
    text = text.replace(/versionCode\s*=\s*\d+/, `versionCode = ${nextCode}`);
  }
  fs.writeFileSync(APP_BUILD_GRADLE, text, 'utf8');
  console.log(
    `[publish] 已更新版本: ${curName}(${curCode}) → ${targetVersion}(${nextCode})`,
  );
  return targetVersion;
}

function gradleAssembleRelease() {
  if (!fs.existsSync(GRADLE_WRAPPER)) {
    die(`未找到 Gradle Wrapper: ${GRADLE_WRAPPER}`);
  }
  console.log('[publish] assembleRelease…');
  run(GRADLE_WRAPPER, ['assembleRelease']);
}

function findBuiltApk(version) {
  const releaseOut = path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'release');
  const preferred = path.join(releaseOut, `reader${version}.apk`);
  if (fs.existsSync(preferred)) {
    return preferred;
  }
  if (!fs.existsSync(releaseOut)) {
    die(`未找到 release 输出目录: ${releaseOut}`);
  }
  const apks = fs
    .readdirSync(releaseOut)
    .filter((f) => f.endsWith('.apk'))
    .map((f) => path.join(releaseOut, f));
  if (apks.length === 0) {
    die(`release 输出目录无 APK: ${releaseOut}`);
  }
  // 取最新修改的
  apks.sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
  console.log(`[publish] 未找到 reader${version}.apk，使用: ${path.basename(apks[0])}`);
  return apks[0];
}

function copyToReleaseDir(srcApk, version) {
  fs.mkdirSync(RELEASE_DIR, { recursive: true });
  const dest = path.join(RELEASE_DIR, `reader${version}.apk`);
  if (fs.existsSync(dest)) {
    fs.unlinkSync(dest);
  }
  fs.copyFileSync(srcApk, dest);
  const st = fs.statSync(dest);
  const mb = (st.size / (1024 * 1024)).toFixed(2);
  console.log(`[publish] 已复制 → ${dest} (${mb} MB)`);
  console.log('[publish] 注意: release/ 已 gitignore，不会提交到 git');
  return dest;
}

function main() {
  const args = process.argv.slice(2).filter((a) => a !== '--force');
  if (args.includes('-h') || args.includes('--help')) {
    console.log(`用法: node publish.js [version] [--force]
示例: node publish.js 1.0.6
产物: release/reader{version}.apk（不上传 git）`);
    process.exit(0);
  }
  const versionArg = args.find((a) => !a.startsWith('-')) || null;
  const version = ensureVersion(versionArg);

  gradleAssembleRelease();
  const built = findBuiltApk(version);
  const dest = copyToReleaseDir(built, version);
  console.log(`[publish] 完成: ${dest}`);
}

main();
