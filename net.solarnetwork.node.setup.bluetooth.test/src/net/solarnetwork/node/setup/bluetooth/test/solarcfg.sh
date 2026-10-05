#!/usr/bin/env sh
#
# Test implementation of solarcfg for Bluetooth setup testing.
#
# Records every invocation's arguments to "$0.log" (one line per call), and
# keeps the simulated radio state in "$0.state" ("true" when enabled).

if [ $# -lt 2 ]; then
	echo "Must provide service and action arguments."  1>&2
	exit 1
fi

SERVICE="$1"
ACTION="$2"
shift 2

LOG="$0.log"
STATE="$0.state"

echo "$SERVICE $ACTION $*" >>"$LOG"

current_state () {
	if [ -e "$STATE" ]; then
		cat "$STATE"
	else
		echo false
	fi
}

do_status () {
	s="$(current_state)"
	echo "active: $s"
	echo "enabled: false"
	echo "discoverable: $s"
	echo "powered: $s"
	echo "adapter: hci0"
}

do_enable () {
	echo true >"$STATE"
	echo "Bluetooth setup radio enabled."
}

do_disable () {
	echo false >"$STATE"
	echo "Bluetooth setup radio disabled."
}

do_restart () {
	if [ -e "$0.fail" ]; then
		echo "Simulated restart failure." 1>&2
		exit 3
	fi
	echo true >"$STATE"
	echo "Bluetooth setup radio restarted."
}

case $ACTION in
	status) do_status "$@";;

	enable) do_enable "$@";;

	disable) do_disable "$@";;

	restart) do_restart "$@";;

	*)
		echo "Action '${ACTION}' not supported." 1>&2
		exit 1
esac
