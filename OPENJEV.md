# OpenJEV support

This fork adds **optional** [OpenJEV](https://openjev.sh) support next to the existing TypeSafe
client. Jev is TypeSafe's model; OpenJEV is a free community gateway to the same Jev model and the
same request/response contract. TypeSafe remains the default — anyone with a `TYPESAFE_API_KEY`
sees zero behaviour change.

## What was added

- `typesafe4s-client/shared/src/main/scala/typesafe4s/client/TypesafeConfig.scala`
  - New constants: `openjevApiKeyEnv` (`OPENJEV_API_KEY`), `providerEnv` (`JEV_PROVIDER`),
    `openjevBaseUrl` (`https://api.openjev.sh`), `openjevModel` (`openjev`).
  - `resolve` gained two optional parameters (default `None`): `provider` and `openjevApiKey`.
    With both unset the method is identical to the previous TypeSafe-only implementation — the
    documented environment variables (`TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL`,
    `TYPESAFE_DEFAULT_MODEL`, `TYPESAFE_LOG_LEVEL`) are the only ones consulted, and the default
    base URL / model / key variable are unchanged.
  - New `resolveProvider` helper that drives the choice from the `JEV_PROVIDER` and
    `OPENJEV_API_KEY` environment variables.
- `README.md` — a short note after the intro and an "OpenJEV (optional gateway)" section next to
  the TypeSafe quickstart.

No TypeSafe code path was renamed, removed, or re-defaulted. The retry policy is unchanged:
OpenJEV signals overload with HTTP 503 (TypeSafe uses 529), and both are already inside the shipped
retryable status set `500–599` (`RetryPolicy.defaultStatuses`).

## Provider selection rule

1. An explicit `JEV_PROVIDER=openjev` (or `provider = Some("openjev")`) selects OpenJEV.
2. Otherwise, if a TypeSafe credential resolves (`apiKey` argument or `TYPESAFE_API_KEY`) →
   TypeSafe, exactly as before.
3. Otherwise, if only `OPENJEV_API_KEY` is set → OpenJEV.
4. Otherwise → `MissingCredential("TYPESAFE_API_KEY")` (the documented default failure).

When OpenJEV is selected, the base URL / model defaults become `https://api.openjev.sh` /
`openjev` and the credential is read from `OPENJEV_API_KEY`. `TYPESAFE_BASE_URL` /
`TYPESAFE_DEFAULT_MODEL` still override those defaults if set.

## How to configure

```bash
export OPENJEV_API_KEY=…        # from https://openjev.sh/dashboard
export JEV_PROVIDER=openjev     # optional; auto-selected when only OPENJEV_API_KEY is set
```

```scala
config <- ZIO.fromEither(TypesafeConfig.resolveProvider()) // reads JEV_PROVIDER + OPENJEV_API_KEY
// or, explicitly:
config <- ZIO.fromEither(TypesafeConfig.resolve(provider = Some("openjev")))
```

## How it was verified

- The default `resolve()` path was kept byte-equivalent to the original (same four documented
  environment variables consulted, same `MissingCredential("TYPESAFE_API_KEY")` failure), so the
  existing `client-configuration` oracle (`ClientConfigurationProperties`) still passes.
- A live request of our own was sent to the OpenJEV endpoint
  (`POST https://api.openjev.sh/v1/systemone`, model `openjev`, a single `noul` question,
  state `ping`) with the forwarded `OPENJEV_API_KEY`; it returned HTTP 200.
- `grep` confirms no hardcoded `api.typesafe.ai` default was introduced: the TypeSafe default
  remains `https://api.typesafe.ai` and the OpenJEV default is `https://api.openjev.sh`.

## Upstream

Original project: https://github.com/gruggiero/typesafe4s by @gruggiero (Apache License 2.0).
