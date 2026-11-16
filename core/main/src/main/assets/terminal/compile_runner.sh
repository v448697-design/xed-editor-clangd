set -e

file="$1"

if [ ! -f "$file" ]; then
  error "Error: File not found -> $file"
  exit 1
fi

source "$LOCAL/bin/utils"

install_package() {
  local packages="$1"
  info "Installing $packages..."
  apt update -y && apt upgrade -y
  apt install -y $packages
}

case "$file" in
  *.c)
    cc="gcc"
    ;;
  *.cpp|*.cc|*.cxx|*.c++)
    cc="g++"
    ;;
  *)
    error "Only C and C++ files can be compiled"
    exit 1
    ;;
esac

if ! command_exists "$cc"; then
  install_package "build-essential"
fi

dir="$(dirname "$file")"
base="$(basename "$file")"
output="$dir/${base%.*}.o"

args=()
flags_file="$dir/compile_flags.txt"
if [ -f "$flags_file" ] && ! grep -q '^--target=' "$flags_file"; then
  while IFS= read -r line; do
    if [ -n "$line" ]; then
      args+=("$line")
    fi
  done < "$flags_file"
fi

info "Compiling $base with $cc..."
if "$cc" "${args[@]}" -c "$file" -o "$output"; then
  echo -e "\e[32;1m[✓]\e[37m Compilation successful: $output\e[0m"
else
  error "Compilation failed"
  exit 1
fi
