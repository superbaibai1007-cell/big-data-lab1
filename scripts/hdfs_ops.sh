#!/usr/bin/env bash
# Shell counterparts of HdfsCli; run only against your experiment filesystem.
set -euo pipefail
die() { printf '%s\n' "$*" >&2; exit 1; }
need() { [[ "$#" -eq "$1" ]] || die 'Wrong argument count'; }
parent() { hdfs dfs -mkdir -p "$(dirname "$1")"; }
isfile() { hdfs dfs -test -f "$1" || die "Not a regular file: $1"; }
mark() { hdfs dfs -setfattr -n user.lab.createdAt -v "$(date -u +%FT%TZ)" "$1"; }
case "${1:-help}" in
  upload)
    [[ $# == 4 && -f $2 ]] || die 'upload LOCAL HDFS append|overwrite'
    [[ $4 == append || $4 == overwrite ]] || die 'Choose append or overwrite'
    parent "$3"
    if hdfs dfs -test -e "$3"; then
      isfile "$3"
      if [[ $4 == append ]]; then hdfs dfs -appendToFile "$2" "$3"
      else hdfs dfs -put -f "$2" "$3"; mark "$3"; fi
    else hdfs dfs -put "$2" "$3"; mark "$3"; fi
    ;;
  download)
    [[ $# == 3 ]] || die 'download HDFS LOCAL_DIR'
    isfile "$2"; mkdir -p "$3"
    name=$(basename "$2"); stem=$name; ext=''
    if [[ $name == *.* && $name != .* ]]; then stem=${name%.*}; ext=.${name##*.}; fi
    n=0; target=$3/$name
    # noclobber atomically reserves a previously absent filename.
    while ! (set -o noclobber; : > "$target") 2>/dev/null; do
      n=$((n+1)); target=$3/${stem}_${n}${ext}
    done
    if ! hdfs dfs -cat "$2" > "$target"; then rm -- "$target"; exit 1; fi
    printf '%s\n' "$target"
    ;;
  cat) [[ $# == 2 ]] || die 'cat HDFS'; isfile "$2"; hdfs dfs -cat "$2" ;;
  stat)
    [[ $# == 2 ]] || die 'stat HDFS'
    hdfs dfs -ls "$2"
    hdfs dfs -stat 'size=%b mtime=%y name=%n' "$2"
    # This is an application-recorded creation time, not native HDFS birth time.
    hdfs dfs -getfattr -n user.lab.createdAt "$2" || echo 'created=UNKNOWN'
    ;;
  list)
    [[ $# == 2 ]] || die 'list HDFS_DIR'
    hdfs dfs -ls -R "$2"
    hdfs dfs -find "$2" -print | while IFS= read -r entry; do
      if hdfs dfs -test -f "$entry"; then
        hdfs dfs -getfattr -n user.lab.createdAt "$entry" || echo "created=UNKNOWN $entry"
      fi
    done
    ;;
  create)
    [[ $# == 2 ]] || die 'create HDFS'
    if hdfs dfs -test -e "$2"; then die 'File already exists'; fi
    parent "$2"; hdfs dfs -touchz "$2"; mark "$2"
    ;;
  delete) [[ $# == 2 ]] || die 'delete HDFS'; isfile "$2"; hdfs dfs -rm "$2" ;;
  mkdir) [[ $# == 2 ]] || die 'mkdir HDFS_DIR'; hdfs dfs -mkdir -p "$2" ;;
  rmdir) [[ $# == 2 ]] || die 'rmdir HDFS_DIR'; hdfs dfs -rmdir "$2" ;;
  add)
    [[ $# == 4 && -f $3 ]] || die 'add HDFS LOCAL_CONTENT begin|end'
    isfile "$2"
    if [[ $4 == end ]]; then hdfs dfs -appendToFile "$3" "$2"
    elif [[ $4 == begin ]]; then
      scratch=$(mktemp -d); stage="${2}.prepend-$(cat /proc/sys/kernel/random/uuid)"
      # Build and verify staging before replacing; shell mv has no overwrite option.
      backup="${2}.backup-$(cat /proc/sys/kernel/random/uuid)"
      trap 'rm -f -- "$scratch/new"; rmdir -- "$scratch"' EXIT
      cat -- "$3" > "$scratch/new"
      hdfs dfs -cat "$2" >> "$scratch/new"
      hdfs dfs -put "$scratch/new" "$stage"
      perm=$(hdfs dfs -stat '%a' "$2"); hdfs dfs -chmod "$perm" "$stage"
      created=$(hdfs dfs -getfattr -d "$2" | sed -n 's/^user.lab.createdAt="\(.*\)"$/\1/p')
      [[ -z $created ]] || hdfs dfs -setfattr -n user.lab.createdAt -v "$created" "$stage"
      hdfs dfs -mv "$2" "$backup"
      if hdfs dfs -mv "$stage" "$2"; then hdfs dfs -rm "$backup"
      else hdfs dfs -mv "$backup" "$2"; die "Replacement failed; recover staging at $stage"; fi
    else die 'Choose begin or end'; fi
    ;;
  move)
    [[ $# == 3 ]] || die 'move SOURCE DESTINATION'; isfile "$2"
    if hdfs dfs -test -e "$3"; then die 'Destination exists'; fi
    parent "$3"; hdfs dfs -mv "$2" "$3"
    ;;
  *) printf '%s\n' 'upload download cat stat list create delete mkdir rmdir add move' ;;
esac
