(ns ^:figwheel-no-load cryptocurrently.dev
  (:require
    [cryptocurrently.core :as core]
    [devtools.core :as devtools]))


(enable-console-print!)

(devtools/install!)

(core/init!)
