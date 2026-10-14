# Neton repository guidance

For Neton implementation, integration and review work, use the installed `neton-development` skill, or read `skills/neton-development/SKILL.md` in a compatible neton-docs checkout. The canonical source and installation guide are at https://github.com/netonframework/netonframework.github.io/tree/main/skills. Do not assume a sibling checkout exists or install tools without user approval. Canonical specifications also live in neton-docs.

Inspect the current dependency/API version and working tree. Follow accepted contracts within their stated scope, distinguish drafts from shipped behavior, and preserve unrelated changes. Generated sources are outputs, not edit targets.

This repository retains only this entry point; neton-docs owns the skill body. A product's business requirements and narrower repository instructions still apply. Run checks proportionate to the actual change and report only observed results.
