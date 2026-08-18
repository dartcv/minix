#!/system/bin/sh

package_name="${1:-com.minitech.miniworld}"
iterations="${2:-200}"
delay="${3:-0.1}"

i=0
last=""
seen=0
while [ "$i" -lt "$iterations" ]; do
    target_pid="$(pidof "$package_name" 2>/dev/null)"
    if [ -n "$target_pid" ]; then
        seen=1
        modules="$(
            grep -E 'lib(libGameApp|GameApp|tprt|Client|tersafe|anogs|unity|mono).*\.so' \
                "/proc/$target_pid/maps" 2>/dev/null \
                | sed -E 's#^.*/##' \
                | sort -u \
                | tr '\n' ','
        )"
        current="$target_pid:$modules"
        if [ "$current" != "$last" ]; then
            echo "T=$i $current"
            last="$current"
        fi
    elif [ "$seen" -eq 1 ]; then
        echo "T=$i EXIT"
        exit 0
    fi
    i=$((i + 1))
    sleep "$delay"
done

echo "T=$i TIMEOUT seen=$seen"
