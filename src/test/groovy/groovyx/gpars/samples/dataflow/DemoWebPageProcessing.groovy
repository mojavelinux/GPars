// GPars - Groovy Parallel Systems
//
// Copyright © 2008-10  The original author or authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package groovyx.gpars.samples.dataflow

import groovyx.gpars.dataflow.DataFlowStream
import static groovyx.gpars.dataflow.DataFlow.operator
import static groovyx.gpars.dataflow.DataFlow.task

/**
 * Builds a network of dataflow operators, which will in turn complete provided urls, download them, search for the words
 * 'groovy' and 'scala' in them and returning reports telling, which site refers to which of the two languages.
 *
 * You might consider checking out the speculative web page processing demo, which uses advanced techniques
 * to speed-up information retrieval using speculation and confirmation technique,
 * described by Greg Barish in his paper (see http://www.jroller.com/vaclav/entry/speculate_on_information_in_parallel)
 *
 * @author Vaclav Pech
 * Date 22nd Sep 2010
 */

final DataFlowStream urlsRequests = new DataFlowStream()
final DataFlowStream urls = new DataFlowStream()
final DataFlowStream pagesForGroovy = new DataFlowStream()
final DataFlowStream pagesForScala = new DataFlowStream()
final DataFlowStream results = new DataFlowStream()
final DataFlowStream reports = new DataFlowStream()

def urlResolver = operator(inputs: [urlsRequests], outputs: [urls]) {
    bindOutput([url: "http://www.${it}.com"])
}

def downloader = operator(inputs: [urls], outputs: [pagesForGroovy, pagesForScala]) {
    def content = it.url.toURL().text
    it.content = content
    bindAllOutputs it
}

def groovyScanner = operator(inputs: [pagesForGroovy], outputs: [results]) {
    def foundWord = it.content.toLowerCase().contains('groovy') ? 'groovy' : ''
    bindOutput([url: it.url, foundWord: foundWord])
}
def scalaScanner = operator(inputs: [pagesForScala], outputs: [results]) {
    def foundWord = it.content.toLowerCase().contains('scala') ? 'scala' : ''
    bindOutput([url: it.url, foundWord: foundWord])
}

def reportsInProgress = [:]

def reporter = operator(inputs: [results], outputs: [reports]) {
    def relatedReportInProgress = reportsInProgress[it.url]
    if (relatedReportInProgress != null) {
        def result
        if (relatedReportInProgress) {
            result = it.foundWord ? relatedReportInProgress + " and ${it.foundWord}" : relatedReportInProgress
        } else {
            result = it.foundWord ?: 'No interesting words'
        }
        reportsInProgress.remove(it.url)
        bindOutput "$result found at ${it.url}"

    } else {
        reportsInProgress[it.url] = it.foundWord
    }
}

task {
    for (;;) {
        println reports.val
    }
}

['dzone', 'infoq', 'jetbrains', 'oracle'].each {
    urlsRequests << it
}