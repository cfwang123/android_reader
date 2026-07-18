#!/usr/bin/env node
'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const IS_WIN = process.platform === 'win32';
const GRADLE_WRAPPER = path.join(ROOT, IS_WIN ? 'gradlew.bat' : 'gradlew');

const APP_ID = 'com.whj.reader';
const MAIN_ACTIVITY = `${APP_ID}/.MainActivity`;
const RELEASE_APK_DIR = path.join(ROOT, 'release');
const RELEASE_APK_NAME = 'whj-reader.apk';

const HELP = `
文本阅读器 Android 构建脚本

用法:
  node build.js <command> [options]

命令:
  build     编译 APK（默认 release）
  rebuild   清理后重新编译（clean + build）
  clean     清理构建产物
  run       安装到真机并启动（debug 包）
  apk       编译 release 并输出到 release/whj-reader.apk
  devices   列出已连接的 adb 设备

选项:
  --debug     build / rebuild 时编译 debug 包
  --release   build / rebuild 时编译 release 包（默认）
  -s <serial> run 时指定设备序列号

示例:
  node build.js build --debug
  node build.js run
  node build.js run -s ABC123456789
  node build.js apk
`;

function die(message) {
  console.error(message);
  process.exit(1);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: ROOT,
    stdio: 'inherit',
    shell: IS_WIN,
    ...options,
  });
  if (result.error) {
    die(`执行失败: ${command} ${args.join(' ')}\n${result.error.message}`);
  }
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

function runCapture(command, args) {
  const result = spawnSync(command, args, {
    cwd: ROOT,
    encoding: 'utf8',
    shell: IS_WIN,
  });
  if (result.error) {
    die(`执行失败: ${command} ${args.join(' ')}\n${result.error.message}`);
  }
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
  return result.stdout;
}

function gradle(tasks) {
  if (!fs.existsSync(GRADLE_WRAPPER)) {
    die(`未找到 Gradle Wrapper: ${GRADLE_WRAPPER}`);
  }
  const taskList = Array.isArray(tasks) ? tasks : [tasks];
  run(GRADLE_WRAPPER, taskList);
}

function adb(args, deviceSerial) {
  const adbArgs = deviceSerial ? ['-s', deviceSerial, ...args] : args;
  run('adb', adbArgs);
}

function capitalize(value) {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

function parseArgs(argv) {
  const positional = [];
  let variant = 'release';
  let deviceSerial = null;

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--debug') {
      variant = 'debug';
    } else if (arg === '--release') {
      variant = 'release';
    } else if (arg === '-s') {
      deviceSerial = argv[i + 1];
      if (!deviceSerial) {
        die('缺少 -s 参数的设备序列号');
      }
      i += 1;
    } else if (arg === '--help' || arg === '-h') {
      console.log(HELP.trim());
      process.exit(0);
    } else {
      positional.push(arg);
    }
  }

  return { command: positional[0], variant, deviceSerial };
}

function getRealDevices() {
  const output = runCapture('adb', ['devices']);
  const devices = [];

  for (const line of output.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('List of devices')) {
      continue;
    }
    const [serial, state] = trimmed.split(/\s+/);
    if (!serial || state !== 'device') {
      continue;
    }
    if (serial.startsWith('emulator-')) {
      continue;
    }
    devices.push(serial);
  }

  return devices;
}

function resolveDevice(serial) {
  const devices = getRealDevices();

  if (devices.length === 0) {
    die('未检测到已连接的真机。请开启 USB 调试并确认 adb devices 可见。');
  }

  if (serial) {
    if (!devices.includes(serial)) {
      die(`设备 ${serial} 未连接或不是可用真机。\n当前真机: ${devices.join(', ')}`);
    }
    return serial;
  }

  if (devices.length > 1) {
    die(`检测到多台真机，请用 -s 指定序列号:\n${devices.join('\n')}`);
  }

  return devices[0];
}

function getApkPath(variant) {
  return path.join(
    ROOT,
    'app',
    'build',
    'outputs',
    'apk',
    variant,
    `app-${variant}.apk`,
  );
}

function build(variant) {
  gradle(`assemble${capitalize(variant)}`);
  const apkPath = getApkPath(variant);
  if (fs.existsSync(apkPath)) {
    console.log(`\nAPK: ${apkPath}`);
  }
}

function apk() {
  gradle('assembleRelease');
  const src = getApkPath('release');
  if (!fs.existsSync(src)) {
    die(`未找到 release APK: ${src}`);
  }
  fs.mkdirSync(RELEASE_APK_DIR, { recursive: true });
  const dest = path.join(RELEASE_APK_DIR, RELEASE_APK_NAME);
  fs.copyFileSync(src, dest);
  console.log(`\nRelease APK: ${dest}`);
}

function clean() {
  gradle('clean');
}

function rebuild(variant) {
  clean();
  build(variant);
}

function runOnDevice(deviceSerial) {
  const serial = resolveDevice(deviceSerial);
  console.log(`目标设备: ${serial}`);
  gradle('installDebug');
  adb(['shell', 'am', 'start', '-n', MAIN_ACTIVITY], serial);
}

function listDevices() {
  const output = runCapture('adb', ['devices', '-l']);
  console.log(output.trim() || '无设备');
}

const { command, variant, deviceSerial } = parseArgs(process.argv.slice(2));

if (!command) {
  console.log(HELP.trim());
  process.exit(0);
}

switch (command) {
  case 'build':
    build(variant);
    break;
  case 'rebuild':
    rebuild(variant);
    break;
  case 'clean':
    clean();
    break;
  case 'run':
    runOnDevice(deviceSerial);
    break;
  case 'apk':
    apk();
    break;
  case 'devices':
    listDevices();
    break;
  default:
    die(`未知命令: ${command}\n${HELP.trim()}`);
}
