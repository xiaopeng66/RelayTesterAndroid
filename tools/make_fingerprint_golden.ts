// Generate golden vectors for the Kotlin port by running UPSTREAM's own detector.
//
// This deliberately imports `shared-detector.ts` from an upstream checkout instead
// of re-implementing the formulas: a golden vector produced by a second
// implementation only proves the two implementations agree, not that either matches
// upstream. The Kotlin test then has to reproduce `ranking`, `scores`, the
// calibrated probability and the candidate order from the packed package.
//
// The detector JSON passed in must be the *unquantized* subset (what
// `build_fingerprint_asset.py --emit-detector-json` writes), while the package the
// app reads is quantized. So the assertions in the Kotlin test double as the
// measurement of what quantization costs.
//
// Run with Bun (the upstream sources are TypeScript):
//   bun run tools/make_fingerprint_golden.ts <upstream-dir> <detector.json> \
//       <bank.json> <unified_reference.jsonl> <out.json> [--cases N]
import { readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const args = process.argv.slice(2)
const caseFlag = args.indexOf('--cases')
const caseCount = caseFlag >= 0 ? Number(args[caseFlag + 1]) : 3
const positional = args.filter((_, i) => !(args[i].startsWith('--') || (caseFlag >= 0 && i === caseFlag + 1)))
if (positional.length !== 5) {
  console.error('usage: bun run tools/make_fingerprint_golden.ts <upstream-dir> <detector.json> <bank.json> <unified_reference.jsonl> <out.json> [--cases N]')
  process.exit(2)
}
const [upstreamDir, detectorPath, bankPath, referencePath, outPath] = positional

const upstream = await import(pathToFileURL(join(resolve(upstreamDir), 'shared-detector.ts')).href)
const artifact = JSON.parse(readFileSync(detectorPath, 'utf8'))
const bank = JSON.parse(readFileSync(bankPath, 'utf8'))

if (bank.built_at !== artifact.bank_built_at) {
  // `supportsSharedDetector` silently falls back to the legacy ranker when these
  // disagree, which would produce golden vectors for an algorithm the app does not
  // implement. Fail loudly instead.
  throw new Error(`bank.built_at (${bank.built_at}) != artifact.bank_built_at (${artifact.bank_built_at})`)
}

/** Samples per model id, taken from upstream's enrolled reference batches. */
const byModel = new Map<string, { challenge_id: string; expected_count: number; text: string }[]>()
for (const line of readFileSync(referencePath, 'utf8').split('\n')) {
  if (!line.trim()) continue
  const batch = JSON.parse(line)
  const id = batch.model?.id
  if (!id) continue
  const list = byModel.get(id) ?? []
  for (const sample of batch.samples ?? []) {
    if (typeof sample.text !== 'string' || !sample.text.trim()) continue
    // One condition per challenge keeps the three answers independent, which is what
    // the real flow produces (three separate challenges).
    if (list.some((s) => s.challenge_id === sample.challenge_id)) continue
    list.push({ challenge_id: sample.challenge_id, expected_count: sample.expected_count ?? 0, text: sample.text })
  }
  byModel.set(id, list)
}

const lcg = (seed: number, length: number, span: number, offset: number) => {
  let s = seed * 7919
  const out: number[] = []
  for (let i = 0; i < length; i += 1) {
    s = (s * 1103515245 + 12345) % 2147483648
    out.push(offset + (s % span))
  }
  return `[${out.join(',')}]`
}

type Case = { name: string; source: string; answers: string[]; expectedCounts: number[] }
const cases: Case[] = []

for (const model of bank.models.slice(0, caseCount)) {
  const samples = byModel.get(model.id) ?? []
  if (samples.length < 3) continue
  cases.push({
    name: `reference-${model.id}`,
    source: `unified_reference.jsonl: ${model.id} (${samples.slice(0, 3).map((s) => s.challenge_id).join(', ')})`,
    answers: samples.slice(0, 3).map((s) => s.text),
    expectedCounts: samples.slice(0, 3).map((s) => s.expected_count),
  })
}

// Shape coverage: prose-wrapped lists, a short answer and a high-value range, none of
// which come from a real model but all of which exercise the parser and features.
cases.push({
  name: 'prose-wrapped',
  source: 'synthetic',
  answers: [
    `Here are 300 numbers as requested:\n\n[${lcg(3, 300, 355, 1).slice(1, -1)}]\n\nLet me know if you need more.`,
    `Sure! ${lcg(4, 260, 60, 1).slice(1, -1)}`,
    lcg(5, 320, 56, 300),
  ],
  expectedCounts: [300, 260, 320],
})

const partial = cases.find((c) => c.name.startsWith('reference-'))
if (partial) {
  cases.push({
    name: 'partial-two-answers',
    source: `unified_reference.jsonl: ${partial.name} (first two only)`,
    answers: partial.answers.slice(0, 2),
    expectedCounts: partial.expectedCounts.slice(0, 2),
  })
}

const round = (value: number) => Number(value.toPrecision(12))

const golden = cases.map((testCase) => {
  const outputs = testCase.answers.map((text, i) => ({ text, expected_count: testCase.expectedCounts[i] }))
  const analysis = upstream.analyzeSharedOutputs(outputs, bank, artifact, { allowPartial: true })
  const results = analysis.results ?? []
  return {
    name: testCase.name,
    source: testCase.source,
    answers: testCase.answers,
    expectedCounts: testCase.expectedCounts,
    expected: {
      method: analysis.method,
      decision: analysis.decision,
      probabilityStatus: analysis.probability_status ?? null,
      prediction: analysis.prediction ?? null,
      predictionName: analysis.prediction_name ?? null,
      familyPrediction: analysis.family_prediction ?? null,
      rankingScore: analysis.ranking_score ?? null,
      verificationTop: analysis.verification_top ?? null,
      // Candidate order is by ranking, which is what the panel displays.
      order: results.map((r: any) => r.model),
      candidateScores: results.map((r: any) => ({
        model: r.model,
        rankingScore: round(r.score),
        verificationScore: r.verification_score == null ? null : round(r.verification_score),
        probability: r.probability == null ? null : round(r.probability),
      })),
      verifierFeatures: results.map((r: any) => (r.verification_features ?? []).map(round)),
    },
  }
})

writeFileSync(outPath, JSON.stringify(golden, null, 2))
console.log(`wrote ${outPath} with ${golden.length} cases`)
for (const entry of golden) {
  console.log(`  ${entry.name.padEnd(34)} top1=${entry.expected.prediction} method=${entry.expected.method}`)
  console.log(`    order: ${entry.expected.order.slice(0, 5).join(', ')}`)
}
