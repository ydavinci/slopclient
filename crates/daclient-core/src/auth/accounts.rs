//! Account list (`accounts.json`) plus encrypted tokens (`secrets.dat`).

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use super::{msa, secrets, xbox};
use crate::{Error, Paths, Result, instance::now, launch::LaunchAuth};

/// Refresh the Minecraft token when it has less than this many seconds left.
const EXPIRY_MARGIN_SECS: u64 = 300;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    /// Minecraft UUID without dashes.
    pub uuid: String,
    pub username: String,
    #[serde(default)]
    pub skin_url: Option<String>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountsFile {
    accounts: Vec<Account>,
    active: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Tokens {
    msa_refresh: String,
    mc_access: String,
    mc_expires_at: u64,
    xuid: Option<String>,
}

pub struct AccountStore<'a> {
    paths: &'a Paths,
}

impl<'a> AccountStore<'a> {
    pub fn new(paths: &'a Paths) -> Self {
        Self { paths }
    }

    pub fn list(&self) -> Result<Vec<Account>> {
        Ok(self.read_accounts()?.accounts)
    }

    pub fn active(&self) -> Result<Option<Account>> {
        let file = self.read_accounts()?;
        let active = file.active.as_deref();
        Ok(file
            .accounts
            .iter()
            .find(|a| Some(a.uuid.as_str()) == active)
            .or(file.accounts.first())
            .cloned())
    }

    /// Finds an account by UUID or (case-insensitive) username.
    pub fn find(&self, query: &str) -> Result<Account> {
        self.list()?
            .into_iter()
            .find(|a| a.uuid == query || a.username.eq_ignore_ascii_case(query))
            .ok_or_else(|| Error::AccountNotFound(query.to_owned()))
    }

    pub fn set_active(&self, uuid: &str) -> Result<()> {
        let mut file = self.read_accounts()?;
        if !file.accounts.iter().any(|a| a.uuid == uuid) {
            return Err(Error::AccountNotFound(uuid.to_owned()));
        }
        file.active = Some(uuid.to_owned());
        self.write_accounts(&file)
    }

    pub fn remove(&self, uuid: &str) -> Result<()> {
        let mut file = self.read_accounts()?;
        file.accounts.retain(|a| a.uuid != uuid);
        if file.active.as_deref() == Some(uuid) {
            file.active = file.accounts.first().map(|a| a.uuid.clone());
        }
        self.write_accounts(&file)?;
        let mut tokens = self.read_tokens()?;
        tokens.remove(uuid);
        self.write_tokens(&tokens)
    }

    /// Full device-code login. `on_code` is called once with the code to show the user.
    pub async fn login_device_code(
        &self,
        client_id: &str,
        on_code: impl FnOnce(&msa::DeviceCode),
    ) -> Result<Account> {
        let code = msa::start_device_code(client_id).await?;
        on_code(&code);
        self.finish_device_code(client_id, &code).await
    }

    /// Second half of the device-code login for UIs that show the code themselves
    /// (get it from [`msa::start_device_code`]). Waits until the user confirmed.
    pub async fn finish_device_code(&self, client_id: &str, code: &msa::DeviceCode) -> Result<Account> {
        let token = msa::poll_device_code(client_id, code).await?;
        self.complete_login(token).await
    }

    async fn complete_login(&self, token: msa::MsaToken) -> Result<Account> {
        let refresh = token
            .refresh_token
            .clone()
            .ok_or_else(|| Error::Auth("no refresh token returned (offline_access scope missing)".into()))?;
        let session = xbox::login(&token.access_token).await?;
        let account = Account {
            uuid: session.profile.id.clone(),
            username: session.profile.name.clone(),
            skin_url: session.profile.active_skin().map(|s| s.url.clone()),
        };

        let mut tokens = self.read_tokens()?;
        tokens.insert(
            account.uuid.clone(),
            Tokens {
                msa_refresh: refresh,
                mc_access: session.access_token,
                mc_expires_at: now() + session.expires_in,
                xuid: session.xuid,
            },
        );
        self.write_tokens(&tokens)?;

        let mut file = self.read_accounts()?;
        file.accounts.retain(|a| a.uuid != account.uuid);
        file.accounts.push(account.clone());
        file.active = Some(account.uuid.clone());
        self.write_accounts(&file)?;
        Ok(account)
    }

    /// Valid launch credentials, refreshing the tokens if they are about to expire.
    pub async fn launch_auth(&self, client_id: &str, uuid: &str) -> Result<LaunchAuth> {
        let mut tokens = self.read_tokens()?;
        let mut entry = tokens
            .get(uuid)
            .cloned()
            .ok_or_else(|| Error::AccountNotFound(uuid.to_owned()))?;
        let mut account = self.find(uuid)?;

        if entry.mc_expires_at <= now() + EXPIRY_MARGIN_SECS {
            let msa_token = msa::refresh(client_id, &entry.msa_refresh).await?;
            let session = xbox::login(&msa_token.access_token).await?;
            entry = Tokens {
                msa_refresh: msa_token.refresh_token.unwrap_or(entry.msa_refresh),
                mc_access: session.access_token,
                mc_expires_at: now() + session.expires_in,
                xuid: session.xuid.or(entry.xuid),
            };
            tokens.insert(uuid.to_owned(), entry.clone());
            self.write_tokens(&tokens)?;

            // Name or skin may have changed since the last login.
            account.username = session.profile.name.clone();
            account.skin_url = session.profile.active_skin().map(|s| s.url.clone());
            let mut file = self.read_accounts()?;
            if let Some(a) = file.accounts.iter_mut().find(|a| a.uuid == uuid) {
                *a = account.clone();
            }
            self.write_accounts(&file)?;
        }

        Ok(LaunchAuth {
            username: account.username,
            uuid: account.uuid,
            access_token: entry.mc_access,
            xuid: entry.xuid,
            user_type: "msa".into(),
        })
    }

    fn read_accounts(&self) -> Result<AccountsFile> {
        match std::fs::read_to_string(self.paths.accounts_file()) {
            Ok(text) => Ok(serde_json::from_str(&text)?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(AccountsFile::default()),
            Err(e) => Err(e.into()),
        }
    }

    fn write_accounts(&self, file: &AccountsFile) -> Result<()> {
        std::fs::create_dir_all(&self.paths.root)?;
        std::fs::write(self.paths.accounts_file(), serde_json::to_string_pretty(file)?)?;
        Ok(())
    }

    fn read_tokens(&self) -> Result<HashMap<String, Tokens>> {
        match std::fs::read(self.paths.secrets_file()) {
            Ok(sealed) => Ok(serde_json::from_slice(&secrets::unprotect(&sealed)?)?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(HashMap::new()),
            Err(e) => Err(e.into()),
        }
    }

    fn write_tokens(&self, tokens: &HashMap<String, Tokens>) -> Result<()> {
        std::fs::create_dir_all(&self.paths.root)?;
        let sealed = secrets::protect(&serde_json::to_vec(tokens)?)?;
        std::fs::write(self.paths.secrets_file(), sealed)?;
        Ok(())
    }
}
