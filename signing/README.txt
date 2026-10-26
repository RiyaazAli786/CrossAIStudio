================================================================================
CrossAIStudio - Release Signing Certificate & Credentials
================================================================================

Keystore File:        crossaistudio-release.jks
Certificate File:     crossaistudio-certificate.cer
Alias:                crossaistudio
Store Password:       CrossAIStudio2026!
Key Password:         CrossAIStudio2026!
Algorithm:            RSA 2048-bit (SHA256withRSA)
Validity:             10,000 days (Until March 13, 2054)

Distinguished Name:
  CN=CrossAIStudio, OU=Mobile, O=CrossAIStudio, L=SanFrancisco, ST=CA, C=US

Certificate Fingerprints:
  SHA1:   7D:B4:4A:F3:25:FE:FD:D8:9A:14:E7:D5:26:8E:5D:EE:96:1F:02:0C
  SHA256: EC:F3:B5:27:ED:8F:02:0F:AD:DE:CC:30:C3:5C:16:B8:70:A4:4F:08:B8:74:DC:CE:4B:F3:5E:1E:6F:52:66:4B

Gradle Configuration:
  The project's app/build.gradle automatically reads signing-credentials.txt
  to sign release builds when running:
  .\gradlew.bat assembleRelease
================================================================================
