# O-Neko ![O-Neko Logo](oneko.svg)

[![CircleCI](https://circleci.com/gh/subshell/o-neko/tree/master.svg?style=svg)](https://circleci.com/gh/subshell/o-neko/tree/master)

O-Neko is a Kubernetes-native application that allows to cross a bridge between developers and other stakeholders by deploying 
development versions of your software into Kubernetes via Helm to allow everybody to try and test them.

|       |       |       |
| ----- | ----- | ----- |
| ![O-Neko login screen](./docs/images/login.png) | ![O-Neko dashboard](./docs/images/dashboard.png) | ![O-Neko project list](./docs/images/projects.png) |

## Documentation and installation guide

[You can find our documentation here.](./docs/DOCUMENTATION.md)

## Features

* Deploy development versions (e.g. every branch) to Kubernetes with one click
* Configure projects via Helm charts
* Automatically re-deploy running versions when the corresponding Docker image has changed (configurable per project and version)
* Automatically stop running versions after a specific time (configurable per project and version)
* Select variables that allow fast changes to frequently used configuration settings (e.g. server URLs)

## Pre-requisites

* Generally, nearly every project able to run in Kubernetes can be deployed with O-Neko
* You need Docker containers of all project versions you want to deploy with O-Neko
* You need Helm charts for each project you want to deploy. The charts need to be hosted in a chart registry.
  * Currently we support standard Helm chart registries and Helm GCS
  * The Docker image tag and the image pull policy need to be configurable
* O-Neko works with kubernetes versions 1.9.0 - 1.19.1 (these versions are officially supported by the client library we use)

## How does it work?

* O-Neko is running inside your Kubernetes cluster
* A project consists of a Docker image that resides in any Docker registry. O-Neko is polling all tags that are available for this image and lets you deploy them.
* The configuration is done via Helm charts (values.yaml) and template variables. O-Neko provides some variables (e.g. the docker image tag) but you can also define your own. The configuration and the variables can be overridden by specific versions.
* The Helm release status of all deployments is monitored by O-Neko and can be seen in the web frontend

## Contributing

If you want to report an issue or work on O-Neko please read our [contributing page](./CONTRIBUTING.md).

## Original authors

This project has been started in late 2017 as a "lab day" project by [@philmtd](https://github.com/philmtd), [@reinkem](https://github.com/reinkem), [@schwerlaut](https://github.com/schwerlaut), [@steffenkuche](https://github.com/steffenkuche) and [@tom-schoener](https://github.com/tom-schoener).
