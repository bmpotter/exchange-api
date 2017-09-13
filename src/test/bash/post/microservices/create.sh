# Adds a microservice
source `dirname $0`/../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "GPS x86_64",
  "description": "blah blah",
  "public": true,
  "specRef": "https://bluehorizon.network/documentation/microservice/gps",
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "singleton",
  "downloadUrl": "",
  "matchHardware": {
    "usbDeviceIds": "1546:01a7",
    "devFiles": "/dev/ttyUSB*"
  },
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",
      "defaultValue": "bar"
    }
  ],
  "workloads": [
    {
      "deployment": "{\"services\":{\"gps\":{\"image\":\"summit.hovitos.engineering/x86/gps:2.0.3\",\"privileged\":true,\"devices\":[\"/dev/bus/usb/001/001:/dev/bus/usb/001/001\"]}}}",
      "deployment_signature": "EURzSkDyk66qE6esYUDkLWLzM=",
      "torrent": "{\"url\":\"https://images.bluehorizon.network/28f57c.torrent\",\"images\":[{\"file\":\"d98bf.tar.gz\",\"signature\":\"kckH14DUj3bX=\"}]}"
    }
  ]
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/microservices | $parse
