#!/usr/bin/env bash
set -euo pipefail

# Configuration
REPO="oscarbol09/jvm-mcp"
BIN_DIR="${HOME}/.local/bin"
EXECUTABLE_NAME="jvm-mcp"
INSTALL_DIR="${HOME}/.local/share/jvm-mcp"

# 1. Detect OS
OS="$(uname -s)"
case "${OS}" in
    Linux*)     OS_NAME="linux" ;;
    Darwin*)    OS_NAME="macos" ;;
    *)          echo "Error: Unsupported OS: ${OS}" >&2; exit 1 ;;
esac

# 2. Detect Architecture
ARCH="$(uname -m)"
case "${ARCH}" in
    x86_64|amd64)   ARCH_NAME="amd64" ;;
    arm64|aarch64)  ARCH_NAME="aarch64" ;;
    *)              echo "Error: Unsupported architecture: ${ARCH}" >&2; exit 1 ;;
esac

RELEASE_ASSET="jvm-mcp-${OS_NAME}-${ARCH_NAME}.tar.gz"
DOWNLOAD_URL="https://github.com/${REPO}/releases/latest/download/${RELEASE_ASSET}"
SHA_URL="https://github.com/${REPO}/releases/latest/download/${RELEASE_ASSET}.sha256"
CHECKSUMS_URL="https://github.com/${REPO}/releases/latest/download/checksums.txt"

# 3. Setup temporary workspace with trap cleanup
TMP_DIR="$(mktemp -d 2>/dev/null || mktemp -d -t 'jvm-mcp')"
cleanup() {
    rm -rf "${TMP_DIR}"
}
trap cleanup EXIT INT TERM

# 4. Download Release Asset
echo "Downloading ${RELEASE_ASSET} from GitHub..."
if ! curl -fsSL "${DOWNLOAD_URL}" -o "${TMP_DIR}/${RELEASE_ASSET}"; then
    echo "Error: Failed to download ${RELEASE_ASSET} from ${DOWNLOAD_URL}" >&2
    echo "Please verify that a release asset exists for ${OS_NAME}-${ARCH_NAME}." >&2
    echo "Note: If the release is still building or the asset has not been published yet, check: https://github.com/${REPO}/releases" >&2
    exit 1
fi

# 5. Fetch and Verify SHA-256 Checksum
echo "Verifying SHA-256 checksum..."
SHA_FILE="${TMP_DIR}/${RELEASE_ASSET}.sha256"

if curl -fsSL "${SHA_URL}" -o "${SHA_FILE}" 2>/dev/null; then
    : # Successfully fetched asset-specific .sha256 file
elif curl -fsSL "${CHECKSUMS_URL}" -o "${TMP_DIR}/checksums.txt" 2>/dev/null; then
    # Fallback to extracting from canonical checksums.txt
    grep "${RELEASE_ASSET}" "${TMP_DIR}/checksums.txt" > "${SHA_FILE}" || {
        echo "Error: ${RELEASE_ASSET} entry not found in checksums.txt" >&2
        exit 1
    }
else
    echo "Error: Could not retrieve SHA-256 checksum file (${RELEASE_ASSET}.sha256 or checksums.txt)" >&2
    exit 1
fi

# Extract expected hash (first space/tab-delimited token)
EXPECTED_HASH="$(awk '{print $1}' "${SHA_FILE}" | tr -d '\r\n ')"
if [[ ! "${EXPECTED_HASH}" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "Error: Checksum file does not contain a valid 64-character SHA-256 hexadecimal hash: '${EXPECTED_HASH}'" >&2
    exit 1
fi

# Compute actual hash using available system utility
ACTUAL_HASH=""
if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_HASH="$(sha256sum "${TMP_DIR}/${RELEASE_ASSET}" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_HASH="$(shasum -a 256 "${TMP_DIR}/${RELEASE_ASSET}" | awk '{print $1}')"
elif command -v openssl >/dev/null 2>&1; then
    ACTUAL_HASH="$(openssl dgst -sha256 "${TMP_DIR}/${RELEASE_ASSET}" | awk '{print $NF}')"
else
    echo "Error: No SHA-256 verification tool available (sha256sum, shasum, or openssl required)." >&2
    exit 1
fi

ACTUAL_HASH="$(echo "${ACTUAL_HASH}" | tr -d '\r\n ')"

if [ "${EXPECTED_HASH}" != "${ACTUAL_HASH}" ]; then
    echo "Error: SHA-256 verification failed!" >&2
    echo "  Expected: ${EXPECTED_HASH}" >&2
    echo "  Actual:   ${ACTUAL_HASH}" >&2
    exit 1
fi

echo "SHA-256 checksum verified successfully."

# 6. Extract into target directory with path traversal protection
echo "Extracting JVM-MCP to ${INSTALL_DIR}..."
mkdir -p "${INSTALL_DIR}"

# Audit archive entries before extraction
while IFS= read -r entry; do
    case "$entry" in
        /*|../*|*/../*)
            echo "Error: Unsafe archive path detected: ${entry}" >&2
            exit 1
            ;;
    esac
done < <(tar -tzf "${TMP_DIR}/${RELEASE_ASSET}")

tar -xzf "${TMP_DIR}/${RELEASE_ASSET}" -C "${INSTALL_DIR}"

# Ensure binary is executable
if [ -f "${INSTALL_DIR}/${EXECUTABLE_NAME}" ]; then
    chmod +x "${INSTALL_DIR}/${EXECUTABLE_NAME}"
fi

# 7. Create Symlink in BIN_DIR
echo "Creating symlink in ${BIN_DIR}..."
mkdir -p "${BIN_DIR}"
if [ -f "${INSTALL_DIR}/${EXECUTABLE_NAME}" ]; then
    ln -sf "${INSTALL_DIR}/${EXECUTABLE_NAME}" "${BIN_DIR}/${EXECUTABLE_NAME}"
elif [ -f "${INSTALL_DIR}/jvm-mcp-${OS_NAME}-${ARCH_NAME}" ]; then
    chmod +x "${INSTALL_DIR}/jvm-mcp-${OS_NAME}-${ARCH_NAME}"
    ln -sf "${INSTALL_DIR}/jvm-mcp-${OS_NAME}-${ARCH_NAME}" "${BIN_DIR}/${EXECUTABLE_NAME}"
else
    echo "Error: Executable not found in unpacked files at ${INSTALL_DIR}" >&2
    exit 1
fi

# 8. Update PATH in shell configuration if necessary (idempotent with markers)
if [[ ":$PATH:" != *":${BIN_DIR}:"* ]]; then
    echo "Adding ${BIN_DIR} to PATH in shell configuration..."

    MODIFIED_RC=false
    for rc in "${HOME}/.bashrc" "${HOME}/.zshrc"; do
        if [ -f "$rc" ]; then
            if ! grep -q "jvm-mcp" "$rc"; then
                {
                    echo ""
                    echo "# >>> jvm-mcp >>>"
                    echo "export PATH=\"${BIN_DIR}:\$PATH\""
                    echo "# <<< jvm-mcp <<<"
                } >> "$rc"
            fi
            MODIFIED_RC=true
        fi
    done

    # If neither ~/.bashrc nor ~/.zshrc existed (e.g. fresh macOS or minimal Linux container)
    if [ "$MODIFIED_RC" = false ]; then
        TARGET_RC="${HOME}/.bashrc"
        if [ "${OS_NAME}" = "macos" ] || [[ "${SHELL:-}" == *"zsh"* ]]; then
            TARGET_RC="${HOME}/.zshrc"
        fi
        touch "$TARGET_RC"
        {
            echo ""
            echo "# >>> jvm-mcp >>>"
            echo "export PATH=\"${BIN_DIR}:\$PATH\""
            echo "# <<< jvm-mcp <<<"
        } >> "$TARGET_RC"
        echo "Created and configured ${TARGET_RC} with ${BIN_DIR} in PATH"
    fi

    echo "Please restart your terminal or run: source ~/.bashrc (or ~/.zshrc)"
fi

echo "Successfully installed ${EXECUTABLE_NAME} to ${BIN_DIR}"
echo "Run '${EXECUTABLE_NAME} --help' to get started."
