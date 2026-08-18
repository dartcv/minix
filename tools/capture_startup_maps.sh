#!/system/bin/sh

package_name="${1:-com.minitech.miniworld}"
iterations="${2:-400}"
delay="${3:-0.005}"

i=0
seen=0
last_state=""
while [ "$i" -lt "$iterations" ]; do
    target_pid="$(pidof "$package_name" 2>/dev/null)"
    if [ -n "$target_pid" ]; then
        seen=1
        maps_file="/proc/$target_pid/maps"
        state="pid"
        grep -q '/libtprt.so' "$maps_file" 2>/dev/null && state="tprt"
        grep -q '/liblibGameApp.so' "$maps_file" 2>/dev/null && state="gameapp"
        if [ "$state" != "$last_state" ]; then
            echo "T=$i PID=$target_pid STATE=$state"
            grep -E '/libtprt\.so|/liblibGameApp\.so' "$maps_file" 2>/dev/null
            if [ "$state" = "gameapp" ]; then
                gameapp_start="$(grep '/liblibGameApp.so' "$maps_file" 2>/dev/null | awk '$2 == "r-xp" && $3 == "00000000" { split($1, range, "-"); print range[1]; exit }')"
                if [ -n "$gameapp_start" ]; then
                    bss_start="$(printf '%x' $((0x$gameapp_start + 0x0a85d000)))"
                    bss_end="$(printf '%x' $((0x$gameapp_start + 0x0a85e000)))"
                    echo "EXPECTED_BSS=$bss_start-$bss_end"
                    grep "^$bss_start-$bss_end " "$maps_file" 2>/dev/null
                fi
            fi
            echo "---"
            last_state="$state"
        fi
        if [ "$state" = "gameapp" ]; then
            exit 0
        fi
    elif [ "$seen" -eq 1 ]; then
        echo "T=$i EXIT"
        exit 0
    fi
    i=$((i + 1))
    sleep "$delay"
done

echo "T=$i TIMEOUT seen=$seen"
