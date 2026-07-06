# GitHub Actions workflows

## Why this directory lives at the repository root

GitHub only honors `.github/workflows/*` at the **repository root**. The
architecture document (`architecture.md` §"Project Structure" line 822)
references `platform/ci-cd/.github/workflows/ci.yml`, but that path is not
honored by the GitHub Actions runner.

The actual working workflow file lives here: `.github/workflows/ci.yml`.
The `platform/ci-cd/` directory is the **documentation home** for CI/CD
material (see `platform/ci-cd/README.md`). Per-service overlays would
land under `platform/ci-cd/.github/` as artifacts, not as workflows.

This is a **documented deviation** from `architecture.md`, not a contradiction.