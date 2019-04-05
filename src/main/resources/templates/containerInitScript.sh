#!/bin/sh
CONTAINER_NAME="--CONTAINER_NAME--"

CONTAINER_VERSION="--CONTAINER_VERSION--"
SERVER_DOWNLOAD="--SERVER_DOWNLOAD--"

SERVER_BASE="--SERVER_BASE--"

echo "Init Script for $CONTAINER_NAME"

if [[ -z $SERVER_BASE ]]; then
    echo "Please specify the source directory to use as the server base (leave blank to skip):"
    read SERVER_BASE
fi

if [[ ! -z $SERVER_BASE ]]; then
    echo "Copying files from $SERVER_BASE..."
    cp -a -n $SERVER_BASE .
fi

if [[ ! -z $SERVER_DOWNLOAD ]]; then
    echo "Downloading server jar..."
    wget $SERVER_DOWNLOAD
    if [[ ! $? -eq 0 ]]; then
        echo "Failed to download server"
    fi
fi

echo "Downloading container plugin..."
wget -O plugins/MineTileContainer.jar "https://github.com/InventivetalentDev/MineTileContainer/releases/download/$CONTAINER_VERSION/container-$CONTAINER_VERSION.jar"
if [[ ! $? -eq 0 ]]; then
    echo "Failed to download container plugin"
fi


echo "Done! You can now start the server :)"