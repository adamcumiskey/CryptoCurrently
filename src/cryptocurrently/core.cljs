(ns cryptocurrently.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as r]
            [chord.client :refer [ws-ch]]
            [cljs.core.async :refer [<! >! close! chan]]
            [goog.string :as gstring]
            [goog.string.format]))

(def ws-feed-url "wss://ws-feed.gdax.com")
(def products ["BTC-USD" "ETH-USD" "LTC-USD"])
(def events ["ticker"])

(defonce channel (atom (chan))) 

;; Store prices in Reagent atoms for UI binding
(defonce btc-price (r/atom 0))
(defonce ltc-price (r/atom 0))
(defonce eth-price (r/atom 0))

;; -------------------------
;; Websockets

(defn connect [ch url]
  "Establish connection to websocket and set value of ch"
  (go 
    (let [{:keys [ws-channel error]} (<! (ws-ch url {:format :json}))]
      (if-not error
        (reset! ch ws-channel)
        (js/console.log error)))))

(defn subscribe [ch product-ids events]
  "Subscribe to events for the given product-ids.
   Required to be sent within 5s of connecting."
  (go
    (>! ch {:type "subscribe"
            :product_ids product-ids
            :channels events}))) 

(defmulti event-handler (fn [message log-enabled] [(message "type") (message "product_id")]))
(defmethod event-handler ["ticker" "BTC-USD"] [message log-enabled]
  (reset! btc-price (message "price"))
  (when (true? log-enabled) (prn (str "Bitcoin: $" (message "price")))))
(defmethod event-handler ["ticker" "LTC-USD"] [message log-enabled]
  (reset! ltc-price (message "price"))
  (when (true? log-enabled) (prn (str "Litecoin: $" (message "price")))))
(defmethod event-handler ["ticker" "ETH-USD"] [message log-enabled]
  (reset! eth-price (message "price"))
  (when (true? log-enabled) (prn (str "Ethereum: $" (message "price")))))
(defmethod event-handler :default [message log-enabled]
  (when (true? log-enabled) (prn (str "Unhandled message: " message))))

(defn handle-events [ch]
  "Dispatch messages from ch to the event-handler"
  (go-loop []
    (when-let [{:keys [message]} (<! ch)]
      (event-handler message true)
      (recur))))

(defn start-updating [ch products events]
  "Connect to the websocket feed.
   On success, start the event-hander and subscribe
   to a feed."
  (add-watch ch nil
    (fn [k r os ch]
      (if-not (nil? ch)
        (do
          (handle-events ch)
          (subscribe ch products events)))))
  (connect ch ws-feed-url))

;; -------------------------
;; Views

(defn currency-row [name price]
  "Div for a single currency"
  [:div
   {:class "currency-element col-4"}
   [:h2 name]
   [:h3
    (str "$" (gstring/format "%.2f" @price))]])

(defn nav-bar []
  [:div
   ; {:class "nav-bar"}
     [:h1 "CryptoCurrently"]])

(defn home-page []
  [:div 
    [nav-bar]
    [:div
      {:class "content"}
      [currency-row "Bitcoin" btc-price]
      [currency-row "Litecoin" ltc-price]
      [currency-row "Ethereum" eth-price]]])

;; -------------------------
;; Initialize app

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (mount-root)
  (start-updating channel products events))
