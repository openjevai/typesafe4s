package typesafe4s.client

import scala.concurrent.duration.*

import typesafe4s.{ApiKey, RetryPolicy, TypesafeException}

// ============================================================================
// spec: effect-portability — Implementation Anchors: `TypesafeConfig`
//
// The operand every client construction takes. This is the operand shape
// only: how a config is produced (env/resource resolution, redaction,
// lifecycle) belongs to spec 7, not here.
// ============================================================================
final case class TypesafeConfig(
  apiKey: ApiKey,
  baseUrl: String,
  model: String,
  allowance: FiniteDuration,
  retryPolicy: RetryPolicy,
  headers: List[(String, String)],
  logLevel: LogLevel
) {

  /**
    * The only string form — the secret never appears (spec:
    * client-configuration — a rendering of a configuration, alone or
    * inside a larger structure, replaces the secret with a marker while
    * the non-secret settings stay readable). The WHOLE render is
    * scrubbed: a configured header or setting that happens to contain
    * the secret's characters is covered too.
    */
  override def toString: String =
    apiKey.redact(
      s"TypesafeConfig($apiKey,$baseUrl,$model,$allowance,$retryPolicy,$headers,$logLevel)"
    )
}

// ============================================================================
// spec: client-configuration — Credential/resolve + the documented variables
// and defaults (concepts/credential.md value domains). ONE resolution order
// for every setting (tier-2 obligation): argument > environment > default.
// ============================================================================
object TypesafeConfig {

  // the documented environment variables — the ONLY names the default
  // `resolve` consults. (OpenJEV adds two OPTIONAL variables, consulted only
  // on the OpenJEV path — see `resolveProvider` / `provider` below.)
  val apiKeyEnv   = "TYPESAFE_API_KEY"
  val baseUrlEnv  = "TYPESAFE_BASE_URL"
  val modelEnv    = "TYPESAFE_DEFAULT_MODEL"
  val logLevelEnv = "TYPESAFE_LOG_LEVEL"

  // the documented defaults
  val defaultBaseUrl   = "https://api.typesafe.ai"
  val defaultModel     = "jev-latest"
  val defaultAllowance = 10.seconds
  val defaultLogLevel  = LogLevel.Info

  // --------------------------------------------------------------------------
  // OpenJEV — a free community gateway to the same Jev model TypeSafe hosts.
  // Jev is TypeSafe's model; OpenJEV never replaces TypeSafe. These constants
  // are consulted ONLY when the OpenJEV provider is selected (explicit
  // `provider = Some("openjev")`, or `JEV_PROVIDER=openjev` via
  // `resolveProvider`, or — with no TypeSafe credential — `OPENJEV_API_KEY`).
  // The default `resolve()` path is unchanged: anyone with a TypeSafe key sees
  // zero behaviour change. The request/response contract is identical; only
  // the endpoint, model id, key variable and the retryable overload status
  // differ (OpenJEV signals overload with 503, TypeSafe with 529 — both are
  // already in the shipped retryable set `500–599`).
  // --------------------------------------------------------------------------
  val openjevApiKeyEnv = "OPENJEV_API_KEY"
  val providerEnv      = "JEV_PROVIDER"
  val openjevBaseUrl   = "https://api.openjev.sh"
  val openjevModel     = "openjev"

  /**
    * Resolves a configuration: each setting from the explicit argument, else
    * the documented variable, else the documented default.
    *
    * Left is `MissingCredential` (no credential resolves — names the
    * variable to set) or `InvalidConfiguration` (an environment value cannot
    * be used — names the variable).
    *
    * `environment` is the injectable seam — production reads `sys.env`, the
    * oracle records the consulted names.
    *
    * Provider selection (OpenJEV is optional and never the default):
    *   1. an explicit `provider = Some("openjev")` argument selects OpenJEV;
    *   2. otherwise, if a TypeSafe credential resolves (`apiKey` argument or
    *      `TYPESAFE_API_KEY`) → TypeSafe, exactly as before (default);
    *   3. otherwise, if an `openjevApiKey` argument resolves → OpenJEV;
    *   4. otherwise → `MissingCredential("TYPESAFE_API_KEY")`.
    * When OpenJEV is selected the base URL/model defaults become OpenJEV's
    * (`https://api.openjev.sh`, `openjev`) and the credential is read from
    * `OPENJEV_API_KEY`; `TYPESAFE_BASE_URL` / `TYPESAFE_DEFAULT_MODEL` still
    * override the defaults if set. Use `resolveProvider` to drive the choice
    * from the `JEV_PROVIDER` / `OPENJEV_API_KEY` environment variables.
    */
  def resolve(
    apiKey: Option[String] = None,
    baseUrl: Option[String] = None,
    model: Option[String] = None,
    logLevel: Option[String] = None,
    allowance: FiniteDuration = defaultAllowance,
    retryPolicy: RetryPolicy = RetryPolicy.default,
    headers: List[(String, String)] = Nil,
    environment: String => Option[String] = sys.env.get,
    provider: Option[String] = None,
    openjevApiKey: Option[String] = None
  ): Either[TypesafeException, TypesafeConfig] = {
    // ONE order, one shape for every setting: argument, else the
    // documented variable, else the documented default. The credential
    // alone is presence-checked (a blank string can never construct an
    // ApiKey, so it resolves as absent); other settings are verbatim —
    // a present-but-empty value is a value. The credential resolves
    // first: its absence is reported before any other invalid value.
    import typesafe4s.TypesafeException.{InvalidConfiguration, MissingCredential}

    val explicitOpenjev: Boolean =
      provider.exists(p => p.toLowerCase.trim == "openjev")

    // TypeSafe credential (argument > environment) — the documented default.
    val typesafeKey: Option[ApiKey] =
      apiKey
        .filter(!_.isBlank)
        .orElse(environment(apiKeyEnv).filter(!_.isBlank))
        .map(ApiKey.of)

    // Selection: explicit openjev wins; else TypeSafe if its key resolves;
    // else OpenJEV if an openjev key is supplied; else the TypeSafe
    // MissingCredential (the documented default failure).
    val useOpenjev: Boolean =
      explicitOpenjev || (typesafeKey.isEmpty && openjevApiKey.filter(!_.isBlank).isDefined)

    val (keyE: Either[TypesafeException, ApiKey], baseDefault: String, modelDefault: String) =
      if (useOpenjev) {
        val k = openjevApiKey
          .filter(!_.isBlank)
          .orElse(environment(openjevApiKeyEnv).filter(!_.isBlank))
          .map(ApiKey.of)
          .toRight(MissingCredential(openjevApiKeyEnv): TypesafeException)
        (k, openjevBaseUrl, openjevModel)
      } else
        (typesafeKey.toRight(MissingCredential(apiKeyEnv): TypesafeException), defaultBaseUrl, defaultModel)

    val level = logLevel.orElse(environment(logLevelEnv))
    for {
      key    <- keyE
      parsed <- level match {
                  case None      => Right(defaultLogLevel)
                  case Some(raw) =>
                    LogLevel
                      .parse(raw)
                      .toRight(
                        // the value is echoed into the failure's detail —
                        // scrub it with the resolved credential: an env
                        // value that happens to contain the secret's
                        // characters must not render them
                        InvalidConfiguration(logLevelEnv, s"'${key.redact(raw)}' is not a log level"): TypesafeException
                      )
                }
    } yield TypesafeConfig(
      apiKey = key,
      baseUrl = baseUrl.orElse(environment(baseUrlEnv)).getOrElse(baseDefault),
      model = model.orElse(environment(modelEnv)).getOrElse(modelDefault),
      allowance = allowance,
      retryPolicy = retryPolicy,
      headers = headers,
      logLevel = parsed
    )
  }

  /**
    * Resolves a configuration with OpenJEV provider auto-detection from the
    * environment: `JEV_PROVIDER` selects the provider, `OPENJEV_API_KEY`
    * supplies the OpenJEV credential. With neither set this is identical to
    * `resolve()` (TypeSafe default); with `JEV_PROVIDER=openjev` (or only
    * `OPENJEV_API_KEY` set) it targets the OpenJEV gateway. Explicit
    * arguments still win over the environment, which wins over the default.
    */
  def resolveProvider(
    apiKey: Option[String] = None,
    baseUrl: Option[String] = None,
    model: Option[String] = None,
    logLevel: Option[String] = None,
    allowance: FiniteDuration = defaultAllowance,
    retryPolicy: RetryPolicy = RetryPolicy.default,
    headers: List[(String, String)] = Nil,
    environment: String => Option[String] = sys.env.get
  ): Either[TypesafeException, TypesafeConfig] =
    resolve(
      apiKey = apiKey,
      baseUrl = baseUrl,
      model = model,
      logLevel = logLevel,
      allowance = allowance,
      retryPolicy = retryPolicy,
      headers = headers,
      environment = environment,
      provider = environment(providerEnv),
      openjevApiKey = environment(openjevApiKeyEnv)
    )
}
