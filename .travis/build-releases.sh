#!/bin/bash

set -ev
export VERSION=$(printf $(cat VERSION))

rm -rf releases
mkdir releases

$lein with-profile +cli build
cp target/gml-to-featured-$VERSION-standalone.jar releases/

$lein with-profile +web-jar build
cp target/gml-to-featured-$VERSION-web.jar releases/

$lein with-profile +web-war build
cp target/gml-to-featured-$VERSION.war releases/