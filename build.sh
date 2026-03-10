#!/bin/bash
#
# Builds a standalone headless SysML v2 LSP server JAR from the
# OMG SysML v2 Pilot Implementation (XText-based).
#
# Requirements: JDK 21, Maven, curl
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"
PILOT_DIR="${BUILD_DIR}/SysML-v2-Pilot-Implementation"
OUT_JAR="${SCRIPT_DIR}/sysml-lsp-server.jar"

# Read versions from properties file
get_prop() { grep "^$1=" "${SCRIPT_DIR}/versions.properties" | cut -d= -f2; }

PILOT_REPO="https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation.git"
PILOT_BRANCH="$(get_prop org.omg.sysml.version)"
XTEXT_VERSION="$(get_prop org.eclipse.xtext.version)"
LSP4J_VERSION="$(get_prop org.eclipse.lsp4j.version)"

AADL_LIB_REPO="https://github.com/santoslab/sysml-aadl-libraries.git"
AADL_LIB_BRANCH="$(get_prop org.santoslab.sysml-aadl-libraries.version)"
AADL_LIB_DIR="${BUILD_DIR}/sysml-aadl-libraries"

MAVEN_CENTRAL="https://repo1.maven.org/maven2"

# Ensure JDK 21
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  else
    echo "Error: JAVA_HOME not set and JDK 21 not found at default location"
    exit 1
  fi
fi
export PATH="${JAVA_HOME}/bin:${PATH}"
echo "Using JAVA_HOME=${JAVA_HOME}"
java -version 2>&1 | head -1

mkdir -p "${BUILD_DIR}"

# 1. Clone or update pilot implementation
if [ -d "${PILOT_DIR}/.git" ]; then
  echo "==> Updating pilot implementation..."
  git -C "${PILOT_DIR}" pull --ff-only
else
  echo "==> Cloning pilot implementation..."
  git clone --depth 1 -b "${PILOT_BRANCH}" "${PILOT_REPO}" "${PILOT_DIR}"
fi

# 1b. Clone or update AADL libraries
if [ -d "${AADL_LIB_DIR}/.git" ]; then
  echo "==> Updating AADL libraries..."
  git -C "${AADL_LIB_DIR}" pull --ff-only
else
  echo "==> Cloning AADL libraries..."
  git clone --depth 1 -b "${AADL_LIB_BRANCH}" "${AADL_LIB_REPO}" "${AADL_LIB_DIR}"
fi

# 2. Build with Maven (skip tests)
echo "==> Building pilot implementation..."
cd "${PILOT_DIR}"
mvn clean package -DskipTests -Dxpect.tests.skip=true -T 4 \
  > "${BUILD_DIR}/maven-build.log" 2>&1 \
  || { echo "Maven build failed. See ${BUILD_DIR}/maven-build.log"; exit 1; }
echo "    Build successful."

# 3. Download additional JARs needed for LSP (not in the interactive fat JAR)
LIB_DIR="${BUILD_DIR}/lib"
mkdir -p "${LIB_DIR}"

download() {
  local group_path="$1" artifact="$2" version="$3"
  local jar="${artifact}-${version}.jar"
  local url="${MAVEN_CENTRAL}/${group_path}/${artifact}/${version}/${jar}"
  if [ ! -f "${LIB_DIR}/${jar}" ]; then
    echo "    Downloading ${jar}..."
    curl -sL -o "${LIB_DIR}/${jar}" "${url}"
  fi
}

echo "==> Downloading LSP dependencies..."
download "org/eclipse/xtext" "org.eclipse.xtext.ide" "${XTEXT_VERSION}"
download "org/eclipse/xtext" "org.eclipse.xtext.xbase.ide" "${XTEXT_VERSION}"
download "org/eclipse/lsp4j" "org.eclipse.lsp4j" "${LSP4J_VERSION}"
download "org/eclipse/lsp4j" "org.eclipse.lsp4j.jsonrpc" "${LSP4J_VERSION}"
download "org/eclipse/lsp4j" "org.eclipse.lsp4j.generator" "${LSP4J_VERSION}"

# 4. Assemble fat JAR
echo "==> Assembling LSP server JAR..."
ASSEMBLY_DIR="${BUILD_DIR}/assembly"
rm -rf "${ASSEMBLY_DIR}"
mkdir -p "${ASSEMBLY_DIR}"

# Base: interactive fat JAR (has all runtime deps except LSP)
INTERACTIVE_JAR=$(ls "${PILOT_DIR}"/org.omg.sysml.interactive/target/org.omg.sysml.interactive-*-all.jar)
cd "${ASSEMBLY_DIR}"
jar xf "${INTERACTIVE_JAR}"

# Overlay: LSP dependencies
for jar in "${LIB_DIR}"/*.jar; do
  jar xf "${jar}"
done

# Overlay: IDE modules from the pilot build
for module in org.omg.sysml.xtext.ide org.omg.kerml.xtext.ide org.omg.kerml.expressions.xtext.ide; do
  ide_jar=$(ls "${PILOT_DIR}/${module}/target/${module}"-*-SNAPSHOT.jar | grep -v sources)
  jar xf "${ide_jar}"
done

# Merge ISetup service registrations
SERVICES_DIR="${ASSEMBLY_DIR}/META-INF/services"
mkdir -p "${SERVICES_DIR}"
cat > "${SERVICES_DIR}/org.eclipse.xtext.ISetup" <<'EOF'
org.omg.sysml.xtext.ide.SysMLIdeSetup
org.omg.kerml.xtext.ide.KerMLIdeSetup
org.omg.kerml.expressions.xtext.ide.KerMLExpressionsIdeSetup
org.eclipse.xtext.xbase.ide.XtypeIdeSetup
org.eclipse.xtext.xbase.ide.XbaseIdeSetup
org.eclipse.xtext.xbase.annotations.ide.XbaseWithAnnotationsIdeSetup
EOF

# Compile and add custom launcher + workspace config factory
echo "==> Compiling custom server classes..."
"${JAVA_HOME}/bin/javac" -cp "${ASSEMBLY_DIR}" \
  -d "${ASSEMBLY_DIR}" \
  "${SCRIPT_DIR}"/src/*.java \
  > "${BUILD_DIR}/javac.log" 2>&1 \
  || { echo "Compilation failed. See ${BUILD_DIR}/javac.log"; exit 1; }

# Create manifest
mkdir -p "${ASSEMBLY_DIR}/META-INF"
cat > "${ASSEMBLY_DIR}/META-INF/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
Main-Class: SysMLServerLauncher
EOF

# Remove signature files that break fat JARs
rm -f "${ASSEMBLY_DIR}"/META-INF/*.SF "${ASSEMBLY_DIR}"/META-INF/*.DSA "${ASSEMBLY_DIR}"/META-INF/*.RSA

# Add license files
cp "${SCRIPT_DIR}/LICENSE" "${ASSEMBLY_DIR}/META-INF/LICENSE"
cp "${SCRIPT_DIR}/LICENSE-GPL" "${ASSEMBLY_DIR}/META-INF/LICENSE-GPL"

# 5. Stage libraries (standard + AADL)
LIB_STAGING="${BUILD_DIR}/library-staging"
rm -rf "${LIB_STAGING}"
mkdir -p "${LIB_STAGING}"

echo "==> Staging libraries..."
cp -a "${PILOT_DIR}/sysml.library" "${LIB_STAGING}/sysml.library"
rm -rf "${LIB_STAGING}/sysml.library/output" \
       "${LIB_STAGING}/sysml.library/.git"* \
       "${LIB_STAGING}/sysml.library/.project" \
       "${LIB_STAGING}/sysml.library/.settings" \
       "${LIB_STAGING}/sysml.library/.workspace.json"

cp -a "${AADL_LIB_DIR}/aadl.library" "${LIB_STAGING}/aadl.library"
cp -a "${AADL_LIB_DIR}/hamr.aadl.library" "${LIB_STAGING}/hamr.aadl.library"
rm -rf "${LIB_STAGING}/aadl.library/.project" \
       "${LIB_STAGING}/aadl.library/.settings" \
       "${LIB_STAGING}/hamr.aadl.library/.project" \
       "${LIB_STAGING}/hamr.aadl.library/.settings"

# Embed libraries inside the JAR under sysml-libraries/
echo "==> Embedding libraries in JAR..."
mkdir -p "${ASSEMBLY_DIR}/sysml-libraries"
cp -a "${LIB_STAGING}/sysml.library" "${ASSEMBLY_DIR}/sysml-libraries/sysml.library"
cp -a "${LIB_STAGING}/aadl.library" "${ASSEMBLY_DIR}/sysml-libraries/aadl.library"
cp -a "${LIB_STAGING}/hamr.aadl.library" "${ASSEMBLY_DIR}/sysml-libraries/hamr.aadl.library"

# Package JAR
echo "==> Packaging ${OUT_JAR}..."
cd "${ASSEMBLY_DIR}"
jar cfm "${OUT_JAR}" META-INF/MANIFEST.MF .
echo "    Done. $(du -h "${OUT_JAR}" | cut -f1) JAR created."

# 6. Package standalone library zip (for external use)
LIB_ZIP="${SCRIPT_DIR}/sysml.library.zip"
echo "==> Packaging library zip..."
cd "${LIB_STAGING}"
zip -qr "${LIB_ZIP}" sysml.library aadl.library hamr.aadl.library
echo "    Done. $(du -h "${LIB_ZIP}" | cut -f1) library zip created."

echo ""
echo "Run with: java -jar ${OUT_JAR}"
