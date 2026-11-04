# LINKA web companion

This is a responsive browser-based companion interface for the LINKA Android project.

## Run locally

Open `index.html` in a modern browser, or serve this directory with any static web server. It has no build step and no external runtime dependencies.

## Current scope

The page provides a browser view of LINKA's product areas and truthful implementation status: overview, nearby network, identity, calling and mesh roadmap. It intentionally does not fabricate signed-in users, connected phones, call states, device identities or relay routes.

## Platform boundary

The web companion is not the Android radio client. Google Nearby Connections and Android Keystore APIs used by the Android app are not directly available to an ordinary webpage. The browser version currently cannot scan nearby LINKA phones, perform the Android security-code pairing flow, or make SIM-free offline mesh calls.

A future full web client requires a separately designed browser identity/session flow, secure API integration where appropriate, and a browser-compatible transport. Browser WebRTC calling over the internet is a distinct feature and must not be presented as SIM-free offline mesh calling.

## Hosting

The site is static and can be hosted by GitHub Pages, Render Static Sites or another static host. Hosting it does not make the Android offline radio network available in a browser.
