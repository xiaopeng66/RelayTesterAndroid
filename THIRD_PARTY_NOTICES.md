# 第三方许可声明 / Third-Party Notices

本应用包含以下第三方作品。依其许可要求，在此保留原始版权与许可全文。

---

## lm-detector —— 模型指纹检测

- 上游项目：https://github.com/Ikaleio/lm-detector
- 版权：Copyright (c) 2026 xqy2006
- 许可：MIT License
- 使用范围：
  - `tools/build_fingerprint_asset.py` + `tools/update_fingerprint_package.py` —— 把上游发布的 `data/shared_detector.json`、`data/unified_bank.json` 构建成端侧二进制检测包（`LMFPA002`）并发布的脚本。
  - 检测包本体不随本应用分发，而是作为发布资产（`bank` 预发布）单独提供，由用户在应用内下载；其中包含上游数据的 Hellinger 特征均值/尺度、LDA 权重、质心与干扰子空间、序数块统计、每模型参考矩阵、核验器张量与温度参数。
  - `core/fingerprint/`（`FingerprintBank.kt`、`SharedScoring.kt`、`NumberFeatures.kt`、`ChallengeGenerator.kt`、`FingerprintTypes.kt`）—— 对上游 `shared/fingerprint-core.js`、`shared-detector.ts`、`challenge-browser.js` 的 Kotlin 移植。
  - `app/src/test/resources/fingerprint-golden.json` + `tools/make_fingerprint_golden.ts` —— 用上游自己的 `analyzeSharedOutputs`（`shared-detector.ts`）生成的测试向量与其生成脚本。

### 相对上游的修改（MIT 允许，此处如实声明）

1. **算法完全对齐上游**：排序器 `0.5·z(LDA) + 0.25·z(kNN距离) + 0.25·z(质心基线)`、均只用前 128 个整数算 LDA、按回答取中位数/均值、核验器的 6→1 逻辑头与温度 softmax，均与上游 `shared-detector-v1` 一致。移植的正确性由"上游自己跑出来的黄金向量"对拍（最差偏差：排名 2.5e-5、检验 3.9e-5、置信度 1.2e-5；120 个候选对 0 反序）。
2. **参数定点量化**：稠密浮点以 `SCALE = 1_000_000` 量化为 int32；两个参考张量按行 16 位量化 + 每行一个 float32 尺度（这是包体积的主要来源，8 位可省一半但会重排约 0.05% 的近邻候选对）。
3. **置信度绑定的处理**：上游用四个哈希把温度参数绑到拟合时的制品上；本应用的检测包本身就是那个绑定的产物，因此这一层退化为"包能解析且温度在 [0.001, 1000] 内"。已在 `SharedScoring.kt` 文件头注明。
4. **警告词不翻译**：挑战提示词的措辞保持上游原文（中/英/日/韩/法五种）。参考库的干扰子空间正是在这些提示词环境下拟合的，改写措辞会偏离其标定前提。

上述移植与数据处理若造成任何不一致，责任在本项目而非上游作者。

```
MIT License

Copyright (c) 2026 xqy2006

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 检测结论的适用范围

指纹检测是**参考库内的封闭集合排序**，不是身份证明：

- 不在参考库中的模型同样会得到一个「最接近的候选」。
- 同家族相邻版本的区分尤其不可靠。
- 检测结果受采样随机性影响，同一模型多次检测可能给出不同候选。

界面上的置信度是库内归一化分数，不代表模型确属该候选的概率。
