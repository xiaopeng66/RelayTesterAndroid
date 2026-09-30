# 第三方许可声明 / Third-Party Notices

本应用包含以下第三方作品。依其许可要求，在此保留原始版权与许可全文。

---

## lm-detector —— 模型指纹检测

- 上游项目：https://github.com/Ikaleio/lm-detector
- 版权：Copyright (c) 2026 xqy2006
- 许可：MIT License
- 使用范围：
  - `app/src/main/assets/lm-fingerprint/lite-bank.bin` —— 由上游发布的参考库数据（Hellinger 特征均值/尺度、LDA 权重、质心、干扰子空间、序数块统计、温度参数等）量化打包而成的端侧二进制资产。
  - `core/fingerprint/`（`FingerprintBank.kt`、`NumberFeatures.kt`、`ChallengeGenerator.kt`、`FingerprintTypes.kt`）—— 对上游 `shared/fingerprint-core.js`、`shared-detector.ts`、`challenge-browser.js` 的 Kotlin 移植。
  - `tools/build_fingerprint_asset.py` —— 生成上述资产的构建脚本。

### 相对上游的修改（MIT 允许，此处如实声明）

1. **移除 kNN 项**：上游排序器为 `0.5·z_LDA + 0.25·z_kNN + 0.25·z_centroid`。kNN 参考数据占上游 ranker 体积约 17.4 MB（19 MB 中的绝大部分），而留出集实测其为净负项（弃用后 49/53 优于保留的 48/53），故端侧不打包 kNN，其权重折入 centroid 项，成为 `0.5·z_LDA + 0.5·z_base`。
2. **不打包 verifier**：上游另有 12.8 MB 的 verifier 模型。上游自身文档承认存在「仅给出传统排名」的降级模式，故端侧略去 verifier，概率显示沿用参考库自带温度参数的 softmax。
3. **参数定点量化**：所有浮点参数以 `SCALE = 1_000_000` 量化为 int32，round=6 位；实测 53/53 模型排名与全精度一致，最大分数漂移 3.6e-5。
4. **警告词不翻译**：挑战提示词的措辞保持上游原文（中/英/日/韩/法五种）。参考库的干扰子空间正是在这十二种提示词环境下拟合的，改写措辞会偏离其标定前提。

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
