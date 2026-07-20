# OCR 走 NPU / NNAPI 尝试记录

> 本文记录阅读器 OCR（PP-OCRv4 mobile / TFLite）在真机上尝试 NPU 加速的过程、结论与踩坑。  
> **产品代码中已移除 NNAPI/NPU 路径**（见 `TfliteOcrEngine`，现仅 GPU → CPU），本文仅作经验留存。

## 1. 目标与背景

| 项 | 说明 |
|----|------|
| 模型 | Umi-OCR Rapid 同源 PP-OCRv4 mobile：`det` / `cls` / `rec` 三个 `.tflite` |
| 默认资产 | float 模型（约 det 4.7MB / cls 0.55MB / rec 10.8MB） |
| 期望 | 利用高通 NPU（Hexagon 等）缩短单图识别耗时 |
| 入口 | Android TFLite 的 **NNAPI**（非厂商 QNN 专有 API） |
| 压测 | `OcrActivity` `exported` + adb `auto_bench`（assets `ocr/bench.jpg`）；曾有 `tmp/ocr_npu_bench.py` |

设备侧可观测到 NPU 相关 prop 已打开、NNAPI feature level 较高（实验时约 L7），说明「硬件上有 NPU / 系统支持 NNAPI」，不等于任意 TFLite 图都能整图下沉。

## 2. 尝试过的绑定方式

在 `TfliteOcrEngine` 中曾实现多级回退（`Backend.AUTO` / 强制 `NNAPI`）：

1. **`Interpreter.Options.setUseNNAPI(true)`**（旧 API）  
2. **`NnApiDelegate`**：`allowFp16`、`useNnapiCpu=true`、`EXECUTION_PREFERENCE_SUSTAINED_SPEED`  
3. **混合**：det/cls 走 NNAPI，rec 强制 CPU（rec 算子更易导致整图 apply 失败）  
4. **混合 + Delegate**：同上，但 det/cls 用 `NnApiDelegate`  
5. **GPU**：`CompatibilityList` + `GpuDelegate`；探测不支持时再 **强制** `GpuDelegate()`  
6. **最终回落 CPU**（XNNPACK / 多线程）

创建意图：优先让 det（卷积密集）上加速器，识别（rec）算子杂、动态性更强，允许单独回 CPU。

## 3. float 模型上的现象

| 后端 | 结果 |
|------|------|
| NNAPI（flag / Delegate / 混合） | 常见 **`Error applying delegate`** 或创建/暖机失败；部分路径日志显示已回退到 CPU |
| GPU | CompatibilityList 报不支持，或 apply/跑通失败 |
| CPU | **可用**；单图端到端约 **~0.7s** 量级（与图内容、分辨率有关） |

结论（float）：

- **不能**把「prop 里有 NPU + NNAPI Level 高」当成能加速。  
- 失败点多在 **delegate 应用 / 图编译 / 首推 allocate**，不是业务后处理。  
- 生产路径若把 NNAPI 排在 AUTO 最前，只会多几次失败重试，**拖慢冷启动**，推理仍落 CPU。

## 4. int8 量化后再试 NPU/GPU

假设 float 图算子不被 NNAPI/GPU 接受，于是做了 **全图 int8 量化** 再装机对比。

### 4.1 转换链路（PC）

- 起点：ONNX（与 Rapid 一致的 mobile 结构）  
- 工具链大致：`onnx2tf` → TensorFlow SavedModel → `TFLiteConverter` full-integer  
- 注意点（转换期踩坑）：
  - 环境依赖 `tf_keras` 等与 TF 版本对齐  
  - ONNX 节点名需清洗（非法字符）  
  - 采用 **权重量化 + 激活 int8，I/O 仍 float** 一类配置，便于应用侧少改预处理  
  - det 输出 layout 曾出现 **`[1,1,H,W]`** 与原先 float 图不一致，Kotlin 侧 `runDetMap` / 暖机按 **输出 tensor shape** 分配缓存需跟着改  

相关一次性脚本曾在工程 `tmp/ocr_tflite/`（`convert_int8.py`、`quantize_full_int8.py` 等）；中间产物在仓库外 `tmp_ocr_int8`，float 备份过 `float_assets_backup`。

### 4.2 int8 装机结果

| 维度 | 观察 |
|------|------|
| NNAPI / GPU 能否打开 | **可以**创建并完成推理（相对 float 明显好转） |
| 相对 float-CPU 速度 | **没有变快**；实测约 **慢 30%～40%**（同一设备、同类图） |
| 识别效果 | **变差**（错字、漏行等更明显） |
| 最终取舍 | **恢复 float 资产**；产品去掉 NNAPI |

用户问题「识别速度快多少」的直接回答：**不快，反而更慢一截**。

## 5. 为何「能上 NNAPI」却不快

综合判断（非逐算子 profile 结论，供后续参考）：

1. **部分算子仍回落 CPU**  
   `useNnapiCpu=true` 提高 apply 成功率，也意味着图可能被切成「NPU 碎片 + CPU 段」，**跨设备拷贝 / 同步** 抵消加速。

2. **int8 有额外代价**  
   float I/O 时前后仍有 quant/dequant；校准不佳时既损精度又未换来充分的 NPU 吞吐。

3. **CPU 路径已经很强**  
   TFLite CPU + 多线程对 mobile OCR 这种尺寸，XNNPACK 表现稳定；NPU 只有在 **大算子几乎全下沉** 时才容易赢。

4. **NNAPI ≠ 厂商 NPU 最优路径**  
   真要榨高通 NPU，往往需要 **QNN / 厂商转换工具 + 专有量化配置**，而不是通用 `NnApiDelegate` 盲试。通用 NNAPI 更适合「碰巧兼容」的模型，不适合当唯一加速策略。

5. **GPU 同理**  
   float 时代 GpuDelegate 也经常 apply 失败；int8 能跑 ≠ 比 CPU 快。

## 6. 压测与调试手段（已移除代码时的做法）

曾用：

```text
# 强制后端 + 自动跑 bench 图（示意，参数名以当时 EXTRA_* 为准）
adb shell am start -n com.whj.reader/.OcrActivity \
  --es backend NNAPI \
  --ez auto_bench true
```

日志关键字：

- `OCR_BENCH_BACKEND=...`：实际打开的后端名（可能是 `NNAPI`、`NNAPI+CPU`、`CPU` 等）  
- `OCR_BENCH_FAIL`：引擎加载失败  
- 结果文件中 `detMs` / `recMs` / `totalMs` 与 init 日志  

设备探测示例：

```text
adb shell getprop ro.boot.vendor.qspa.npu
adb shell getprop persist.device_config.nnapi_native.current_feature_level
```

**注意**：prop 只能说明能力存在，不能代替端到端 `totalMs` 对比。

## 7. 当前产品策略（写文档时）

- 推理后端：**AUTO = GPU →（必要时强制 GPU）→ CPU**；无 NNAPI。  
- 模型：**float** `assets/ocr/{det,cls,rec}.tflite`。  
- 扫描版 PDF 等场景：优先缓存 OCR 结果，避免重复推理，比纠结后端更有体感收益。

## 8. 若以后再做 NPU，建议顺序

1. **先 profile**：看 det/rec 分别耗时、是否内存带宽瓶颈。  
2. **官方/社区已验证的 QNN/Hexagon 导出链路**，而不是先上 int8 再赌 NNAPI。  
3. 量化必须 **有校准集**，并做错字率对比（速度提升 10% 但错字翻倍不值得）。  
4. 评估 **只加速 det**（矩形检测）是否够用；rec 继续 CPU 往往更稳。  
5. 用固定 `bench.jpg` + 多轮 warm 后 median 计时，不要只看首次冷启动。  
6. 若仅包体体积：再考虑 int8；**不要把「能 apply NNAPI」当成成功标准**。

## 9. 一句话结论

> 在本项目的 PP-OCRv4 mobile + TFLite 路径上，**NNAPI/NPU 没有带来可用加速**；float 下 delegate 经常挂掉，int8 后虽能跑通但更慢、精度更差。已放弃产品内 NPU 尝试，保留 GPU/CPU 与 float 模型。
