// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `BetterStackConfig`: common settings (SPEC §2.2) plus the Better Stack Logs credentials.
//!
//! Better Stack has two hosts with separate credentials (API.md §1/§2, DECISIONS 1 and 4): the
//! source's ingesting host (`url` + `source_token`, sent as `Authorization: Bearer`) and the SQL
//! query host (`query_url` + `query_username` + `query_password`, sent as HTTP Basic). Each set is
//! all-or-nothing; a client needs at least one of them.

use std::fmt;
use std::path::PathBuf;
use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;

use url::Url;

use crate::core::redact::redact_url;
use crate::core::{
    CommonConfig, CommonConfigBuilder, EnvReader, Error, Hooks, Result, RetryPolicy, Secret,
    TlsConfig,
};

/// Empty on purpose: Better Stack has no global ingest host. Every source has its own ingesting
/// host (for example `https://s1234.eu-nbg-2.betterstackdata.com`), so the ingest URL must be
/// set whenever logs are sent (DECISIONS 4).
pub const DEFAULT_URL: &str = "";

/// Vendor variables checked (in order) after `GROW_BETTER_STACK_LOGS_URL`.
pub const VENDOR_URL_VARS: &[&str] = &["BETTERSTACK_INGESTING_URL", "BETTERSTACK_INGESTING_HOST"];
/// Vendor variables checked after `GROW_BETTER_STACK_LOGS_SOURCE_TOKEN`.
pub const VENDOR_SOURCE_TOKEN_VARS: &[&str] = &["BETTERSTACK_SOURCE_TOKEN"];
/// Vendor variables checked (in order) after `GROW_BETTER_STACK_LOGS_QUERY_URL`.
pub const VENDOR_QUERY_URL_VARS: &[&str] = &["BETTERSTACK_QUERY_URL", "BETTERSTACK_QUERY_HOST"];
/// Vendor variables checked after `GROW_BETTER_STACK_LOGS_QUERY_USERNAME`.
pub const VENDOR_QUERY_USERNAME_VARS: &[&str] = &["BETTERSTACK_QUERY_USERNAME"];
/// Vendor variables checked after `GROW_BETTER_STACK_LOGS_QUERY_PASSWORD`.
pub const VENDOR_QUERY_PASSWORD_VARS: &[&str] = &["BETTERSTACK_QUERY_PASSWORD"];
/// Vendor variables checked after `GROW_BETTER_STACK_LOGS_SOURCE_TABLE`.
pub const VENDOR_SOURCE_TABLE_VARS: &[&str] = &["BETTERSTACK_SOURCE_TABLE"];

/// Whether `s` is a safe ClickHouse identifier (`^[A-Za-z_][A-Za-z0-9_]*$`), the rule for
/// source tables (collections), which cannot be bound as SQL parameters.
pub fn is_valid_identifier(s: &str) -> bool {
    let mut chars = s.chars();
    match chars.next() {
        Some(c) if c.is_ascii_alphabetic() || c == '_' => {}
        _ => return false,
    }
    chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// Normalizes a host or URL: a value containing `://` is used as given, a bare `host[:port]`
/// becomes `https://host[:port]`; trailing slashes are removed. Blank input gives `None`.
pub fn normalize_base_url(value: &str) -> Option<String> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }
    let with_scheme = if trimmed.contains("://") {
        trimmed.to_owned()
    } else {
        format!("https://{trimmed}")
    };
    Some(with_scheme.trim_end_matches('/').to_owned())
}

/// Wire encoding of ingest requests (`POST /` on the ingesting host).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum Encoding {
    /// One JSON array per request (`application/json`). Default.
    #[default]
    Json,
    /// One JSON object per line (`application/x-ndjson`).
    Ndjson,
    /// One MessagePack array per request (`application/msgpack`); `dt` is a MessagePack
    /// timestamp extension.
    Msgpack,
}

impl Encoding {
    /// All encodings.
    pub const ALL: [Encoding; 3] = [Encoding::Json, Encoding::Ndjson, Encoding::Msgpack];

    /// `json`, `ndjson` or `msgpack`.
    pub fn as_str(self) -> &'static str {
        match self {
            Encoding::Json => "json",
            Encoding::Ndjson => "ndjson",
            Encoding::Msgpack => "msgpack",
        }
    }

    /// Content-Type sent for this encoding.
    pub fn content_type(self) -> &'static str {
        match self {
            Encoding::Json => "application/json",
            Encoding::Ndjson => "application/x-ndjson",
            Encoding::Msgpack => "application/msgpack",
        }
    }
}

impl fmt::Display for Encoding {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for Encoding {
    type Err = Error;

    /// Parses `json`, `ndjson` or `msgpack` (case-insensitive; `messagepack` also accepted).
    fn from_str(s: &str) -> Result<Self> {
        match s.trim().to_ascii_lowercase().as_str() {
            "json" => Ok(Encoding::Json),
            "ndjson" => Ok(Encoding::Ndjson),
            "msgpack" | "messagepack" => Ok(Encoding::Msgpack),
            _ => Err(Error::config("encoding must be json, ndjson or msgpack")),
        }
    }
}

/// Immutable client configuration; build it with [`BetterStackConfig::builder`] or
/// [`BetterStackConfig::from_env`]. `Debug` output redacts every credential.
#[derive(Clone)]
pub struct BetterStackConfig {
    common: CommonConfig,
    ingest: Option<CommonConfig>,
    query: Option<CommonConfig>,
    source_token: Option<Secret>,
    query_username: Option<String>,
    query_password: Option<Secret>,
    source_table: Option<String>,
    encoding: Encoding,
}

impl fmt::Debug for BetterStackConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("BetterStackConfig")
            .field("url", &self.url().map(|u| redact_url(u.as_str())))
            .field("source_token", &self.source_token)
            .field(
                "query_url",
                &self.query_url().map(|u| redact_url(u.as_str())),
            )
            .field("query_username", &self.query_username)
            .field("query_password", &self.query_password)
            .field("source_table", &self.source_table)
            .field("encoding", &self.encoding)
            .field("common", &self.common)
            .finish()
    }
}

impl BetterStackConfig {
    /// A builder with nothing set.
    pub fn builder() -> BetterStackConfigBuilder {
        BetterStackConfigBuilder::default()
    }

    /// Configuration from the process environment
    /// (explicit > `GROW_BETTER_STACK_LOGS_*` > vendor variables > defaults).
    pub fn from_env() -> Result<Self> {
        Self::builder().build_from_env()
    }

    /// Settings shared by every Grow Codes SDK; the URL is the ingest URL, or the query URL for
    /// a query-only client.
    pub fn common(&self) -> &CommonConfig {
        &self.common
    }

    /// Common settings of the ingest transport (`None` without ingest credentials).
    pub fn ingest_common(&self) -> Option<&CommonConfig> {
        self.ingest.as_ref()
    }

    /// Common settings of the SQL query transport (`None` without query credentials).
    pub fn query_common(&self) -> Option<&CommonConfig> {
        self.query.as_ref()
    }

    /// Ingest base URL (the source's ingesting host), or `None` for a query-only client.
    pub fn url(&self) -> Option<&Url> {
        self.ingest.as_ref().map(CommonConfig::url)
    }

    /// Source token for the ingesting host.
    pub fn source_token(&self) -> Option<&Secret> {
        self.source_token.as_ref()
    }

    /// SQL query base URL (ClickHouse HTTP interface).
    pub fn query_url(&self) -> Option<&Url> {
        self.query.as_ref().map(CommonConfig::url)
    }

    /// SQL connection username.
    pub fn query_username(&self) -> Option<&str> {
        self.query_username.as_deref()
    }

    /// SQL connection password.
    pub fn query_password(&self) -> Option<&Secret> {
        self.query_password.as_ref()
    }

    /// Default collection (`t1234_myapp`) used by `search`, `search_logs`, `tail` and `ping`.
    pub fn source_table(&self) -> Option<&str> {
        self.source_table.as_deref()
    }

    /// Ingest wire encoding (default [`Encoding::Json`]).
    pub fn encoding(&self) -> Encoding {
        self.encoding
    }

    /// Whether the ingest side (url + source token) is configured.
    pub fn has_ingest(&self) -> bool {
        self.ingest.is_some() && self.source_token.is_some()
    }

    /// Whether the SQL query side (query url + username + password) is configured.
    pub fn has_query(&self) -> bool {
        self.query.is_some() && self.query_username.is_some() && self.query_password.is_some()
    }

    /// `base64(username:password)` for HTTP Basic, or `None` without query credentials.
    pub fn basic_credentials(&self) -> Option<Secret> {
        let (user, pass) = (self.query_username.as_ref()?, self.query_password.as_ref()?);
        Some(Secret::new(crate::http::base64_encode(
            format!("{user}:{}", pass.expose_secret()).as_bytes(),
            false,
        )))
    }

    /// Credential values to redact from error messages and logs.
    pub fn known_secrets(&self) -> Vec<Secret> {
        let mut out: Vec<Secret> = self
            .source_token
            .iter()
            .chain(self.query_password.iter())
            .cloned()
            .collect();
        out.extend(self.basic_credentials());
        out
    }

    /// Checks the service rules: each credential set is all-or-nothing, at least one is
    /// complete, `source_table` is an identifier and credentials are not blank.
    pub fn validate(&self) -> Result<()> {
        let ingest_any = self.ingest.is_some() || self.source_token.is_some();
        let query_any =
            self.query.is_some() || self.query_username.is_some() || self.query_password.is_some();
        if !ingest_any && !query_any {
            return Err(Error::config(
                "invalid configuration: set url + source_token (ingest) and/or query_url + query_username + query_password (SQL query)",
            ));
        }
        let mut problems = Vec::new();
        if ingest_any && self.ingest.is_none() {
            problems.push("url (the source's ingesting host) is required with source_token");
        }
        if ingest_any && self.source_token.is_none() {
            problems.push("source_token is required with url");
        }
        if query_any && !self.has_query() {
            problems.push("query_url, query_username and query_password must be given together");
        }
        if self
            .source_token
            .as_ref()
            .is_some_and(|t| t.expose_secret().trim().is_empty())
        {
            problems.push("source_token must not be blank");
        }
        if self.query_password.as_ref().is_some_and(Secret::is_empty) {
            problems.push("query_password must not be blank");
        }
        if self
            .source_table
            .as_deref()
            .is_some_and(|t| !is_valid_identifier(t))
        {
            problems.push("source_table must match ^[A-Za-z_][A-Za-z0-9_]*$");
        }
        if problems.is_empty() {
            Ok(())
        } else {
            Err(Error::config(format!(
                "invalid configuration: {}",
                problems.join("; ")
            )))
        }
    }
}

/// Builder for [`BetterStackConfig`]. Values set here win over the environment.
#[derive(Debug, Clone, Default)]
pub struct BetterStackConfigBuilder {
    common: CommonConfigBuilder,
    url: Option<String>,
    source_token: Option<Secret>,
    query_url: Option<String>,
    query_username: Option<String>,
    query_password: Option<Secret>,
    source_table: Option<String>,
    encoding: Option<Encoding>,
}

impl BetterStackConfigBuilder {
    /// Ingest base URL: the source's ingesting host (`s1234.eu-nbg-2.betterstackdata.com`); a
    /// value without `://` gets `https://`.
    pub fn url(mut self, url: impl Into<String>) -> Self {
        self.url = Some(url.into());
        self
    }
    /// Source token sent as `Authorization: Bearer <token>` to the ingesting host only.
    pub fn source_token(mut self, token: impl Into<Secret>) -> Self {
        self.source_token = Some(token.into());
        self
    }
    /// SQL query base URL (e.g. `eu-nbg-2-connect.betterstackdata.com`).
    pub fn query_url(mut self, url: impl Into<String>) -> Self {
        self.query_url = Some(url.into());
        self
    }
    /// Username of the SQL connection.
    pub fn query_username(mut self, username: impl Into<String>) -> Self {
        self.query_username = Some(username.into());
        self
    }
    /// Password of the SQL connection (HTTP Basic).
    pub fn query_password(mut self, password: impl Into<Secret>) -> Self {
        self.query_password = Some(password.into());
        self
    }
    /// Default collection (`t1234_myapp`) for queries.
    pub fn source_table(mut self, table: impl Into<String>) -> Self {
        self.source_table = Some(table.into());
        self
    }
    /// Ingest wire encoding (default [`Encoding::Json`]).
    pub fn encoding(mut self, encoding: Encoding) -> Self {
        self.encoding = Some(encoding);
        self
    }
    /// Connect timeout (default 10 s).
    pub fn connect_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.connect_timeout(timeout);
        self
    }
    /// Per-attempt timeout (default 30 s).
    pub fn request_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.request_timeout(timeout);
        self
    }
    /// Whole-call timeout including retries (default 120 s).
    pub fn total_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.total_timeout(timeout);
        self
    }
    /// Retry policy.
    pub fn retry(mut self, retry: RetryPolicy) -> Self {
        self.common = self.common.retry(retry);
        self
    }
    /// Maximum retries (default 3).
    pub fn max_retries(mut self, max_retries: u32) -> Self {
        self.common = self.common.max_retries(max_retries);
        self
    }
    /// TLS settings.
    pub fn tls(mut self, tls: TlsConfig) -> Self {
        self.common = self.common.tls(tls);
        self
    }
    /// Extra trusted CA certificates (PEM file).
    pub fn ca_cert_path(mut self, path: impl Into<PathBuf>) -> Self {
        self.common = self.common.ca_cert_path(path);
        self
    }
    /// Disables TLS verification (tests only).
    pub fn insecure_skip_verify(mut self, skip: bool) -> Self {
        self.common = self.common.insecure_skip_verify(skip);
        self
    }
    /// Proxy URL (environment proxies are ignored).
    pub fn proxy(mut self, proxy: impl Into<String>) -> Self {
        self.common = self.common.proxy(proxy);
        self
    }
    /// Extra request header (sent to both hosts).
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.common = self.common.header(name, value);
        self
    }
    /// Text appended to the User-Agent.
    pub fn user_agent_suffix(mut self, suffix: impl Into<String>) -> Self {
        self.common = self.common.user_agent_suffix(suffix);
        self
    }
    /// Request hooks.
    pub fn hooks(mut self, hooks: Arc<dyn Hooks>) -> Self {
        self.common = self.common.hooks(hooks);
        self
    }
    /// Edits any other common setting (its URL is ignored: use [`url`](Self::url) and
    /// [`query_url`](Self::query_url)).
    pub fn common(mut self, f: impl FnOnce(CommonConfigBuilder) -> CommonConfigBuilder) -> Self {
        self.common = f(self.common);
        self
    }

    /// Builds without reading the environment.
    pub fn build(self) -> Result<BetterStackConfig> {
        let url = self.url.as_deref().and_then(normalize_base_url);
        let query_url = self.query_url.as_deref().and_then(normalize_base_url);
        let side = |url: &Option<String>, name: &str| -> Result<Option<CommonConfig>> {
            match url {
                None => Ok(None),
                Some(u) => self
                    .common
                    .clone()
                    .url(u.clone())
                    .build(u)
                    .map(Some)
                    .map_err(|e| {
                        let msg = e.message().replacen("url", name, 1);
                        e.with_message(format!("invalid configuration: {msg}"))
                    }),
            }
        };
        let ingest = side(&url, "url")?;
        let query = side(&query_url, "query_url")?;
        let common = match (&ingest, &query) {
            (Some(c), _) | (None, Some(c)) => c.clone(),
            (None, None) => {
                // No endpoint at all: validate() reports it; build a placeholder to get there.
                self.common.clone().build("http://unset.invalid")?
            }
        };
        let config = BetterStackConfig {
            common,
            ingest,
            query,
            source_token: self.source_token,
            query_username: self.query_username.filter(|u| !u.trim().is_empty()),
            query_password: self.query_password,
            source_table: self.source_table.filter(|t| !t.is_empty()),
            encoding: self.encoding.unwrap_or_default(),
        };
        config.validate()?;
        Ok(config)
    }

    /// Fills unset values from the process environment, then builds.
    pub fn build_from_env(self) -> Result<BetterStackConfig> {
        self.build_with_env(&EnvReader::new())
    }

    /// Fills unset values from `env`, then builds.
    pub fn build_with_env(mut self, env: &EnvReader) -> Result<BetterStackConfig> {
        self.common = self.common.apply_env(env, VENDOR_URL_VARS)?;
        if self.url.is_none() {
            self.url = env.string("URL", VENDOR_URL_VARS);
        }
        if self.source_token.is_none() {
            self.source_token = env.secret("SOURCE_TOKEN", VENDOR_SOURCE_TOKEN_VARS);
        }
        if self.query_url.is_none() {
            self.query_url = env.string("QUERY_URL", VENDOR_QUERY_URL_VARS);
        }
        if self.query_username.is_none() {
            self.query_username = env
                .string("QUERY_USERNAME", VENDOR_QUERY_USERNAME_VARS)
                .map(|s| s.trim().to_owned());
        }
        if self.query_password.is_none() {
            self.query_password = env.secret("QUERY_PASSWORD", VENDOR_QUERY_PASSWORD_VARS);
        }
        if self.source_table.is_none() {
            self.source_table = env
                .string("SOURCE_TABLE", VENDOR_SOURCE_TABLE_VARS)
                .map(|s| s.trim().to_owned());
        }
        if self.encoding.is_none() {
            if let Some((var, value)) = env.lookup("ENCODING", &[]) {
                self.encoding = Some(value.parse().map_err(|_| {
                    Error::config(format!(
                        "environment variable {var} must be json, ndjson or msgpack"
                    ))
                })?);
            }
        }
        self.build()
    }
}
