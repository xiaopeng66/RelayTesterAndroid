// Emit position-kernel / sequence-feature vectors for the Kotlin port's unit tests.
//
// The two functions are module-private in upstream's `positional.ts`, so this script writes a
// copy of that file with `export` added to the two declarations — right next to the original,
// because the file's relative imports have to keep resolving — and imports the copy. Only that
// one token is added: the arithmetic is upstream's own, untouched, so the vectors are what
// upstream computes rather than what a re-implementation of it computes. The copy is written
// into the upstream checkout (which is a scratch copy, not this repository) and only the
// emitted vectors are committed.
//
// Run with Bun (the upstream sources are TypeScript):
//   bun run tools/make_positional_vectors.ts <upstream-shared-dir> <out.json>
import { readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const [upstreamDir, outPath] = process.argv.slice(2)
const source = readFileSync(join(resolve(upstreamDir), 'positional.ts'), 'utf8')
const exposed = source
  .replace('function positionKernel(', 'export function positionKernel(')
  .replace('function sequenceFeatures(', 'export function sequenceFeatures(')
if (exposed === source) throw new Error('could not expose the two functions')
const exposedPath = join(resolve(upstreamDir), 'positional_exposed_for_vectors.ts')
writeFileSync(exposedPath, exposed)
const upstream = await import(pathToFileURL(exposedPath).href)

/** Deterministic pseudo-random answers, the same generator the golden's prose case uses. */
const lcg = (seed: number, length: number, span: number, offset: number) => {
  let s = seed * 7919
  const out: number[] = []
  for (let i = 0; i < length; i += 1) {
    s = (s * 1103515245 + 12345) % 2147483648
    out.push(offset + (s % span))
  }
  return out
}

const cases: { name: string; note: string; numbers: number[] }[] = [
  { name: 'wide-range-300', note: 'full 1..355 range, prose-case generator', numbers: lcg(3, 300, 355, 1) },
  { name: 'narrow-range-260', note: 'a 60-value band near the top', numbers: lcg(4, 260, 60, 1) },
  { name: 'high-band-320', note: 'a 56-value band near 300', numbers: lcg(5, 320, 56, 300) },
  { name: 'ramp-120', note: 'strictly increasing: every lag autocorrelates', numbers: Array.from({ length: 120 }, (_, i) => 1 + ((i * 353) / 119 | 0)) },
  { name: 'flat-90', note: 'one repeated value: zero variance and all-zero steps', numbers: Array(90).fill(200) },
  { name: 'digits-140', note: 'last digit deliberately cycled', numbers: Array.from({ length: 140 }, (_, i) => 1 + ((i * 7) % 355)) },
  { name: 'short-40', note: 'below the largest lag (34) plus one window', numbers: lcg(9, 40, 355, 1) },
]

const round = (value: number) => Number(value.toPrecision(13))
const out = cases.map((testCase) => ({
  name: testCase.name,
  note: testCase.note,
  numbers: testCase.numbers,
  positionKernel: upstream.positionKernel(testCase.numbers).map(round),
  sequenceFeatures: upstream.sequenceFeatures(testCase.numbers).map(round),
}))

writeFileSync(outPath, JSON.stringify(out, null, 1))
console.log(`wrote ${outPath} with ${out.length} cases`)
for (const entry of out) {
  console.log(`  ${entry.name.padEnd(18)} kernel=${entry.positionKernel.length} sequence=${entry.sequenceFeatures.length}`)
}
