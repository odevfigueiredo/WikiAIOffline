package com.wikiaioffline

import checkBilingual
import checkRag
import org.junit.Test

class CoreRegressionTest {
    @Test fun bilingualRetrieval() = checkBilingual()

    @Test fun groundedAnswer() = checkRag()
}
