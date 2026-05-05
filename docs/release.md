# Release Builds

LibreDrop release builds are produced by `.github/workflows/release.yml`.
The workflow is separate from normal CI and runs when:

- a tag matching `v*` is pushed, such as `v1.2.3`

The workflow builds `:app:assembleRelease` and `:app:bundleRelease`, signs them
with a keystore supplied through GitHub Actions secrets, and uploads the signed
APK and AAB as workflow artifacts. The decoded keystore lives in
`$RUNNER_TEMP/release.keystore` for the Gradle step only and is removed before
artifact upload.

## Required GitHub Secrets

Configure these repository secrets before publishing a release:

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_B64` | Base64-encoded Android release keystore file |
| `KEYSTORE_PASSWORD` | Password for the keystore file |
| `KEY_ALIAS` | Alias of the signing key inside the keystore |
| `KEY_PASSWORD` | Password for the signing key |

Do not commit keystore files or passwords. The workflow decodes
`KEYSTORE_B64` into `$RUNNER_TEMP/release.keystore`, which is outside the
workspace and is discarded with the runner.

## Generate A Keystore

Create a keystore locally if you do not already have one:

```bash
keytool -genkeypair \
  -v \
  -keystore release.keystore \
  -alias libredrop \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Record the keystore password, key alias, and key password in the matching
GitHub secrets.

## Encode The Keystore

GitHub secrets are text-only, so store the binary keystore as base64 text.

On macOS:

```bash
base64 -i release.keystore | pbcopy
```

On Linux:

```bash
base64 -w0 release.keystore
```

Paste the encoded value into the `KEYSTORE_B64` repository secret.

## Local Signed Build

For local release signing, pass the same values as Gradle properties or
environment variables:

```bash
./gradlew :app:assembleRelease \
  -PKEYSTORE_FILE=/absolute/path/to/release.keystore \
  -PKEYSTORE_PASSWORD="$KEYSTORE_PASSWORD" \
  -PKEY_ALIAS="$KEY_ALIAS" \
  -PKEY_PASSWORD="$KEY_PASSWORD"
```

If no signing values are supplied, local release builds remain unsigned.
