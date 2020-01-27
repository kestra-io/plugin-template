# Kestra Plugin Template

<p align="center">
  <img width="460" src="https://github.com/kestra-io/kestra/raw/master/ui/src/assets/logo.svg?sanitize=true"  alt="Kestra workflow orchestrator" />
</p>

> A template for creating plugin for Kestra

This repository is meant to serve as a general template for how to set up new plugin for 
[Kestra](https://github.com/kestra-io/kestra). In general, setting up a new plugin should 
take only a few minutes; use this repository as a way of finding example files to ensure 
that you've set up the plugin correctly.

## Structure 

### Tasks
There only one simple task in this template `io.kestra.task.templates.Example`. 
This tasks will take a `format` options (that is an handlebar string) and will return 
a reverse string in the `RunOutput` vars name `example` mostly to show how to create 
a task for Kestra. The main logic will be in this task. This also show how to included 
dependding libs on the final jar.

### Tests
There is 2 unit test examples in this template : 
- `ExampleTest` : this test will only test the main task, this allow you to send any input 
parameters to your task and test the returning behaviour easily.
- `ExampleRunnerTest`: this test will load all flow located in `src/test/resources/flows/`
and will run an in-memory runner to be able to test a full flow. There is also a 
configuration file in `src/test/resources/application.yml` that is only for the full runner 
test to configure in-memory runner.

### Libs
We may need to use some third party libs in order to develop plugins  
(third party api, helper classes, ...). By default, the generated jar will not contains 
any dependencies. In order to add you dependencies, you will need to add a gradle 
dependencies `implementation` :  
```groovy
    // libs
    implementation group: 'org.apache.commons', name: 'commons-lang3', version: '3.9'
``` 
The other dependencies (especially `kestra.core` & `micronaut`) is configured to be only `compileOnly` 
in order to not be exported with plugins.

Some libs are already included and can be used in any plugins and must not be added as 
dependency in gradle configuration :
* [Guava](https://github.com/google/guava) 
* [Commons IO](https://commons.apache.org/proper/commons-io/)
* [Commons Lang](https://commons.apache.org/proper/commons-lang/)


## License
Apache 2.0 © [Nigh Tech](https://nigh.tech)
