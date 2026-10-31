#!/bin/sh
set -eu
echo "MACHINE_TYPE=${MACHINE_TYPE:-UNSET}"
[ "${MACHINE_TYPE:-}" = "MentorPi_Mecanum" ] || echo "WARNING: expected MACHINE_TYPE=MentorPi_Mecanum"
echo "Input devices:"; arecord -l
echo "Output devices:"; aplay -l
if [ -e /dev/wonderecho ]; then ls -l /dev/wonderecho; else echo "NOTE: /dev/wonderecho is absent; USB audio can still be selected through ALSA parameters."; fi
echo "Test capture: arecord -D <input> -f S16_LE -r 16000 -c 1 -d 5 /tmp/mediassist-test.wav"
echo "Test playback: aplay -D <output> /tmp/mediassist-test.wav"
