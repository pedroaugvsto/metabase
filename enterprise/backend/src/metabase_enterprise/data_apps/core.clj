(ns metabase-enterprise.data-apps.core
  "What other modules may ask the data-apps module."
  (:require
   [metabase-enterprise.data-apps.db :as data-apps.db]))

(defn resource-card-ids
  "The IDs among `card-ids` of cards in a data app's resource collection. Only the app's repository files define
  those, so nothing else may serialize them."
  [card-ids]
  (data-apps.db/resource-card-ids card-ids))
