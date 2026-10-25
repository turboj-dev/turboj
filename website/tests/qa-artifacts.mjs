import { resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

// New validation runs can keep their output separate from historical evidence.
export function artifactDirectory(category, fallback, moduleURL) {
  return process.env.QA_ARTIFACT_ROOT
    ? new URL(`${category}/`, pathToFileURL(resolve(process.env.QA_ARTIFACT_ROOT) + sep))
    : new URL(fallback, moduleURL);
}
