#!/bin/bash

# Create user
groupadd -r decentralchain --gid=999
useradd -r -g decentralchain --uid=999 --home-dir=$WVDATA --shell=/bin/bash decentralchain

# Install DEB packages
#mv /tmp/decentralchain.deb /tmp/decentralchain_original.deb
#mkdir /tmp/decentralchain_path_fix
#dpkg-deb -R /tmp/decentralchain_original.deb /tmp/decentralchain_path_fix
#sed -i 's%^%/%g' /tmp/decentralchain_path_fix/DEBIAN/conffiles
#dpkg-deb -b /tmp/decentralchain_path_fix /tmp/decentralchain.deb
#dpkg -i /tmp/decentralchain.deb || exit 1

mv /tmp/decentralchain.deb /tmp/decentralchain_original.deb
apt-get update && apt full-upgrade -y && apt-get install -y wget
wget https://github.com/Decentral-America/DCC/releases/download/v1.3.5/decentralchain_1.3.5_all.deb -O /tmp/decentralchain_1.3.5_all_downloaded.deb
mkdir /tmp/decentralchain_path_fix
dpkg-deb -R /tmp/decentralchain_1.3.5_all_downloaded.deb /tmp/decentralchain_path_fix
sed -i 's%^%/%g' /tmp/decentralchain_path_fix/DEBIAN/conffiles
dpkg-deb -b /tmp/decentralchain_path_fix /tmp/decentralchain.deb
dpkg -i /tmp/decentralchain.deb || exit 1

if [[ $ENABLE_GRPC == "true" ]]; then
    wget https://github.com/Decentral-America/DCC/releases/download/v1.3.5/decentralchain-grpc-server_1.3.5_all.deb -O /tmp/decentralchain-grpc-server_1.3.5_all_downloaded.deb
    echo "Installing gRPC server"
    dpkg -i /tmp/decentralchain-grpc-server_1.3.5_all_downloaded.deb || exit 1
#    dpkg -i /tmp/decentralchain-grpc-server.deb || exit 1
fi

# Set permissions
chown -R decentralchain:decentralchain $WVDATA $WVLOG && chmod 777 $WVDATA $WVLOG

rm /etc/decentralchain/waves.conf # Remove example config
cp /tmp/entrypoint.sh /usr/share/decentralchain/bin/entrypoint.sh
chmod +x /usr/share/decentralchain/bin/entrypoint.sh

# Cleanup
rm -rf /tmp/*
