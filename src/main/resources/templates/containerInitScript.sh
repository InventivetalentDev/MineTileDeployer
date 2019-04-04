#!/bin/sh
CONTAINER_VERSION="--CONTAINER_VERSION--"

echo "Downloading container plugin..."
wget -O plugins/MineTileContainer.jar https://github.com/InventivetalentDev/MineTileContainer/releases/download/$CONTAINER_VERSION/container-$CONTAINER_VERSION.jar"


echo "Done! You can now start the server :)"