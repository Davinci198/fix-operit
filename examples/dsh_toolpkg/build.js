const fs = require('fs');
const path = require('path');

function extractMetadataBanner(entryFilePath) {
    if (!entryFilePath) return '';
    if (!fs.existsSync(entryFilePath)) return '';

    const src = fs.readFileSync(entryFilePath, 'utf8');
    const match = src.match(/\/\*\s*METADATA[\s\S]*?\*\//);
    return match ? `${match[0]}\n` : '';
}

function resolveRepoRoot() {
    return path.resolve(__dirname, '..', '..');
}

async function main() {
    let esbuild;
    try {
        esbuild = require('esbuild');
    } catch (e) {
        throw new Error('Missing devDependency "esbuild". Install it first: npm i -D esbuild');
    }

    const repoRoot = resolveRepoRoot();
    const examplesDir = path.join(repoRoot, 'examples');
    const pkgDir = path.join(examplesDir, 'dsh_toolpkg');

    const tsconfig = path.join(pkgDir, 'tsconfig.json');
    const outfile = path.join(examplesDir, 'dsh_toolpkg.js');

    const preferredEntry = path.join(pkgDir, 'dsh_toolpkg.ts');

    if (fs.existsSync(preferredEntry)) {
        const metadataBanner = extractMetadataBanner(preferredEntry);
        await esbuild.build({
            absWorkingDir: pkgDir,
            entryPoints: [preferredEntry],
            bundle: true,
            format: 'cjs',
            platform: 'neutral',
            target: ['es2017'],
            outfile,
            banner: { js: metadataBanner },
            tsconfig,
            logLevel: 'info'
        });
        console.log(`✅ Built ${outfile}`);
        return;
    }

    throw new Error(`Entry file not found: ${preferredEntry}`);
}

main().catch((err) => {
    console.error(err && err.stack ? err.stack : String(err));
    process.exitCode = 1;
});
// force rebuild Fri Sep  4 12:17:07 UTC 2026
