(ns cryptocurrently.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as r]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [chord.client :refer [ws-ch]]
            [cljs.core.async :refer [<! >! close! chan]]
            [goog.string :as gstring]
            [goog.string.format]))

;; -------------------------
;; Constants

(def ws-feed-url "wss://ws-feed.gdax.com")
(def products ["BTC-USD" "ETH-USD" "LTC-USD"])
(def events ["ticker" "level2"])
(def visible-orders 10)


;; -------------------------
;; State

(defonce channel (atom (chan))) 

(defonce btc-price (r/atom 0))
(defonce btc-orders (r/atom (ring-buffer visible-orders)))

(defonce ltc-price (r/atom 0))
(defonce ltc-orders (r/atom (ring-buffer visible-orders)))

(defonce eth-price (r/atom 0))
(defonce eth-orders (r/atom (ring-buffer visible-orders)))


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


;; ------------------------
;; Event Handlers

;;;; Ticker

(defmulti ticker-event-handler (fn [message log-enabled] (message "product_id")))

(defmethod ticker-event-handler "BTC-USD" [message log-enabled]
  (reset! btc-price (message "price"))
  (when (true? log-enabled) (prn (str "Bitcoin: $" (message "price")))))

(defmethod ticker-event-handler "LTC-USD" [message log-enabled]
  (reset! ltc-price (message "price"))
  (when (true? log-enabled) (prn (str "Litecoin: $" (message "price")))))

(defmethod ticker-event-handler "ETH-USD" [message log-enabled]
  (reset! eth-price (message "price"))
  (when (true? log-enabled) (prn (str "Ethereum: $" (message "price")))))


;;;; L2Update (buy/sell orders)

(defn format-order [order]
  {:type "order"
   :time (order "time")
   :product_id (order "product_id")
   :order_type (first (first (order "changes")))
   :order_price (second (first (order "changes")))
   :order_amount (last (first (order "changes")))})

(defmulti order-event-handler (fn [message log-enabled] (message "product_id")))

(defmethod order-event-handler "BTC-USD" [message log-enabled]
  (swap! btc-orders conj (format-order message))
  (when (true? log-enabled) (prn "BTC:" (format-order message))))
(defmethod order-event-handler "LTC-USD" [message log-enabled]
  (swap! ltc-orders conj (format-order message))
  (when (true? log-enabled) (prn "LTC:" (format-order message))))
(defmethod order-event-handler "ETH-USD" [message log-enabled]
  (swap! eth-orders conj (format-order message))
  (when (true? log-enabled) (prn "ETH:" (format-order message))))

(defmethod order-event-handler :default [message log-enabled]
  (when (true? log-enabled) (prn (str "Unhandled Order: " (format-order message)))))


;;;; Base

(defmulti event-handler (fn [message log-enabled] (message "type")))

(defmethod event-handler "ticker" [message log-enabled]
  (ticker-event-handler message log-enabled))

(defmethod event-handler "l2update" [message log-enabled]
  (order-event-handler message log-enabled))

(defmethod event-handler :default [message log-enabled]
  (when (true? log-enabled) (prn (str "Unhandled message: " message))))

(defn add-event-handler [ch]
  "Dispatch messages from ch to the event-handler"
  (go-loop []
    (when-let [{:keys [message]} (<! ch)]
      (event-handler message false)
      (recur))))


;; -------------------------
;; Views

(defn order-element [order]
  [:div
   {:style {:display :flex
            :flex-direction :row
            :justify-content :space-around}}
   [:p (order :order_type)]
   [:p (str "$" (gstring/format "%.2f" (order :order_price)))]
   [:p (gstring/format "%.5f" (order :order_amount))]])

(defn orders-element [orders]
  "Order stream for a currency"
  [:div
   {:class "col-4"}
   (for [item @orders]
     ^{:key item} [order-element item])])

(defn currency-element [name price]
  "Div for a single currency"
  [:div
   {:class "currency-element col-4"}
   [:h2 name]
   [:h3
    (str "$" (gstring/format "%.2f" @price))]])

(defn nav-bar []
  [:div
     [:h1 "CryptoCurrently"]])

(defn home-page []
  [:div 
    [nav-bar]
    [:div
      {:class "content"}
      [currency-element "Bitcoin" btc-price]
      [currency-element "Litecoin" ltc-price]
      [currency-element "Ethereum" eth-price]]
    [:div
     [orders-element btc-orders]
     [orders-element ltc-orders]
     [orders-element eth-orders]]])


;; -------------------------
;; Initialize app

(defn start-updating [ch products events]
  "Connect to the websocket feed.
   On success, start the event-hander and subscribe
   to a feed."
  (add-watch ch nil
    (fn [k r os ch]
      (if-not (nil? ch)
        (do
          (add-event-handler ch)
          (subscribe ch products events)))))
  (connect ch ws-feed-url))

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (mount-root)
  (start-updating channel products events))
