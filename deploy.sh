#! /bin/bash

lein clean
lein package
surge -p public/ -d cryptocurrently.surge.sh
