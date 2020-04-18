# botplot

A simple parser of logs to ultimately plot successful/failed login attempts by location.

Tested with CentOS 8 only, via a simple script that can be run on a cron schedule:

```
!#/bin/bash

last --time-format iso -s -1days | tac > last.txt
lastb --time-format iso -s -1days | tac > lastb.txt
cat /var/log/secure | grep "Disconnected from authenticating user" > secure.txt

./AccessTracker.jsh
```
