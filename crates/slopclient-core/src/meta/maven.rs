/// A Maven coordinate `group:artifact:version[:classifier][@extension]`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MavenCoord {
    pub group: String,
    pub artifact: String,
    pub version: String,
    pub classifier: Option<String>,
    pub extension: String,
}

impl MavenCoord {
    pub fn parse(name: &str) -> Option<Self> {
        let (coord, extension) = match name.split_once('@') {
            Some((c, ext)) => (c, ext.to_owned()),
            None => (name, "jar".to_owned()),
        };
        let mut parts = coord.split(':');
        let group = parts.next()?.to_owned();
        let artifact = parts.next()?.to_owned();
        let version = parts.next()?.to_owned();
        let classifier = parts.next().map(str::to_owned);
        if parts.next().is_some() {
            return None;
        }
        Some(Self {
            group,
            artifact,
            version,
            classifier,
            extension,
        })
    }

    pub fn with_classifier(&self, classifier: &str) -> Self {
        Self {
            classifier: Some(classifier.to_owned()),
            ..self.clone()
        }
    }

    /// Repository-relative path, e.g. `org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar`.
    pub fn path(&self) -> String {
        let classifier = self
            .classifier
            .as_ref()
            .map(|c| format!("-{c}"))
            .unwrap_or_default();
        format!(
            "{}/{}/{}/{}-{}{}.{}",
            self.group.replace('.', "/"),
            self.artifact,
            self.version,
            self.artifact,
            self.version,
            classifier,
            self.extension
        )
    }

    /// Identity without the version, used to de-duplicate libraries.
    pub fn key(&self) -> String {
        format!(
            "{}:{}:{}",
            self.group,
            self.artifact,
            self.classifier.as_deref().unwrap_or("")
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_and_builds_paths() {
        let c = MavenCoord::parse("org.lwjgl:lwjgl:3.3.3:natives-windows").unwrap();
        assert_eq!(
            c.path(),
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar"
        );
        let c = MavenCoord::parse("net.fabricmc:intermediary:1.21.4@zip").unwrap();
        assert_eq!(c.path(), "net/fabricmc/intermediary/1.21.4/intermediary-1.21.4.zip");
        assert!(MavenCoord::parse("broken").is_none());
    }
}
