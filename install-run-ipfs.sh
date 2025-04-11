#! /bin/sh
wget https://dist.ipfs.io/kubo/v0.34.1/kubo_v0.34.1_linux-amd64.tar.gz -O /tmp/kubo_linux-amd64.tar.gz
hash="$(sha256sum /tmp/kubo_linux-amd64.tar.gz)"
expected=42045802fe60c64fb01350bc071190c534d600fe269759c06e27e22b2012fd3e
if [[ "$hash" != "$expected" ]]
then
    echo "incorrect ipfs hash!" 1>&2
    exit 64
fi
tar -xvf /tmp/kubo_linux-amd64.tar.gz
export PATH=$PATH:$PWD/kubo/
ipfs init
ipfs daemon --routing=dhtserver &
