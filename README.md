# Tactrix Reader

This project is a prototype for reading the Tactrix OpenPort 2.0 via USB and J2534.

This project is a mono-repo. The first project lives in `lib/device`, which will
handle all code regarding communication with the device and its translation into
Clojure and EDN.

In the future, services and ui will be added as their own directories.
