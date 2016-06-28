#!/bin/bash

HEIM_ROOM=$1

cd "$( dirname "${BASH_SOURCE[0]}" )"

LEIN_FAST_TRAMPOLINE=true lein trampoline \
  run euphoria.io $HEIM_ROOM ~/backup/websites/euphoria/$HEIM_ROOM.jsons
