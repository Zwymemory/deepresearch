# Third-party models, data, and services

The repository's original source code is available under the [MIT License](LICENSE).
That license does not replace the terms attached to third-party models, datasets,
libraries, or hosted APIs.

No model weights, downloaded SciFact corpus, provider credentials, or paid-provider
outputs are distributed in the public repository. Runtime and training scripts fetch
or consume those assets only after the operator supplies them.

## Model and dataset boundaries

| Asset | Upstream terms recorded on 2026-08-22 | How this repository uses it | Release boundary |
|---|---|---|---|
| [`BAAI/bge-reranker-base`](https://huggingface.co/BAAI/bge-reranker-base) | MIT; the model card permits commercial use | Optional Cross-Encoder runtime base and LoRA experiment base | Model files are downloaded at runtime and ignored by Git. Preserve upstream attribution and re-check the pinned revision before distributing derived weights. |
| [SciFact claims and evidence annotations](https://github.com/allenai/scifact/blob/master/LICENSE.md) | CC BY 4.0 | Optional retrieval/training input | The repository ships conversion code only. Distribution of transformed claims requires attribution and a modification notice. |
| [SciFact corpus abstracts](https://github.com/allenai/scifact/blob/master/LICENSE.md) | ODC-By 1.0; the abstracts originate from S2ORC | Optional retrieval/training input | Raw and transformed corpus content is excluded. Review both ODC-By and applicable upstream corpus terms before redistribution or model-weight publication. |
| [SciFact reference code](https://github.com/allenai/scifact/blob/master/LICENSE.md) | Apache License 2.0 | Format and methodology reference; not vendored | Keep its license and notice if code is copied in the future. |
| [BEIR](https://github.com/beir-cellar/beir) | Apache License 2.0 for the framework; each dataset retains its own terms | Dataset download/format entrypoint used by benchmark scripts | BEIR explicitly leaves dataset permission and attribution checks to the user. |

The training manifest generator mirrors these source and license fields in
`reranker-service/training/manifest.py`. A generated manifest is evidence about the
inputs used; it is not itself a legal clearance or permission to publish weights.

## Hosted providers and package dependencies

DeepSeek-compatible chat endpoints, Zhipu embeddings, Tavily search, PostgreSQL,
Elasticsearch, Spring libraries, Python packages, and container base images remain
subject to their own terms. They are referenced through `pom.xml`, Python lock or
requirements files, and Dockerfiles; their binaries are not relicensed by this
project's MIT License.

Before a public release:

1. run the release checks in [`docs/PUBLIC_RELEASE.md`](docs/PUBLIC_RELEASE.md);
2. review upstream terms again if a model/data revision changes;
3. do not publish adapters, merged weights, raw data, provider output, or caches until
   their provenance and redistribution obligations are documented in a release-specific
   manifest.

This file records engineering boundaries and is not legal advice.
