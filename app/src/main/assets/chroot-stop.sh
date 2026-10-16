#!/system/bin/sh
# Force-stop a chroot session: kill every process whose root is the guest rootfs
# and unmount everything under it. Run as root via su. Idempotent -- safe to call
# after a graceful exit already cleaned up.
set -u
ROOT='__ROOT__'

for p in /proc/[0-9]*; do
    pid=${p#/proc/}
    r=$(readlink "$p/root" 2>/dev/null) || continue
    [ "$r" = "$ROOT" ] || continue
    kill -9 "$pid" 2>/dev/null || true
done

awk -v r="$ROOT" '$2==r || index($2,r"/")==1 {print $2}' /proc/mounts 2>/dev/null | sort -r | while read -r m; do
    umount -l "$m" 2>/dev/null || true
done
