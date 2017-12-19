# CryptoCurrently

Display real-time cryptocurrency prices from GDAX.
Uses ClojureScript, core.async, websockets, and Reagent.

[Demo](https://supreme-current.surge.sh/)

### Development mode

To start the Figwheel compiler, navigate to the project folder and run the following command in the terminal:

```
lein figwheel
```

Figwheel will automatically push cljs changes to the browser.
Once Figwheel starts up, you should be able to open the `public/index.html` page in the browser.

### Building for production

```
lein clean
lein package
```
