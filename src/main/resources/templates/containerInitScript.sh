#!/bin/sh
CONTAINER_VERSION="--CONTAINER_VERSION--"


echo "Please specify the source directory to use as the server base (leave blank to skip):"
read serverBase

if [[ ! -z $serverBase ]]; then
    echo "Copying files from $serverBase..."
    cp -a -n serverBase .
fi


echo "Downloading container plugin..."
wget -O plugins/MineTileContainer.jar "https://github.com/InventivetalentDev/MineTileContainer/releases/download/$CONTAINER_VERSION/container-$CONTAINER_VERSION.jar"


echo "Done! You can now start the server :)"