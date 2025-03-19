# jepsen-kiwidb

A Clojure library designed to ... well, that part is up to you.

## Usage

FIXME

## License

Copyright © 2025 FIXME

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.

## run

./genkey.sh

cd docker

docker-compose up -d

docker exec [control] /bin/bash

cd jepsen-kiwidb

lein run test --ssh-private-key /root/.ssh/id_rsa -n n1 -n n2 -n n3 --concurrency 100
