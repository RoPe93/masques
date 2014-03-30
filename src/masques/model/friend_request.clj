(ns masques.model.friend-request
  (:require [clj-time.core :as clj-time]
            [clojure.tools.logging :as logging]
            [korma.core :as korma]
            [masques.model.profile :as profile]
            [masques.model.share :as share])
  (:use masques.model.base))

(def created-at-key :created-at)
(def request-status-key :request-status)
(def requested-at-key :requested-at)
(def request-approved-at-key :request-approved-at)
(def profile-id-key :profile-id)

(def pending-status "pending")
(def pending-acceptance-status "pending-acceptance")

(defn find
  "Returns the friend request with the given id."
  [id]
  (when id
    (find-by-id friend-request id)))

(defn delete-friend-request
  "Deletes the given friend request from the database. The friend request should
include the id."
  [friend-request-record]
  (profile/delete-profile { :id (profile-id-key friend-request-record) })
  (delete-record friend-request friend-request-record))

(defn set-requested-at [record]
  (if (or (requested-at-key record) ((h2-keyword requested-at-key) record))
    record 
    (conj record { (h2-keyword requested-at-key) (str (clj-time/now)) })))

(defn update-requested-at
  "Updateds the given record with requested-at to now."
  [record]
  (update-record friend-request
    { :ID (id record)
      (h2-keyword requested-at-key) (str (clj-time/now)) }))

(defn requested-at
  "Returns when the friend request was received by the target computer and
user."
  [record]
  (requested-at-key record))

(defn save
  "Saves the given friend request to the database."
  [record]
  (insert-or-update friend-request record))

(defn send-request
  "Creates a new friend request and attaches a new profile and new share to it."
  [masques-id-file message]
 ; We need to create a share, attach a friend request and profile to it.
  (when-let [new-profile (profile/load-masques-id-file masques-id-file)]
    (let [new-request (save
                        { request-status-key pending-status
                          profile-id-key (id new-profile) })]
      (share/create-friend-request-share message new-profile new-request))))

(defn receive-request
  "Creates a new incoming friend request"
  [profile message]
  (when-let [new-profile (profile/save profile)]
    (let [new-request (save
                        { request-status-key pending-acceptance-status
                          profile-id-key (id new-profile)
                          requested-at-key (str (clj-time/now)) })]
      (share/create-received-friend-request-share message new-request))))

(defn count-requests
  "Counts all of the requests which satisfies the given korma where-map."
  [where-map]
  (:count
    (first
      (korma/select
        friend-request
        (korma/aggregate (count :*) :count)
        (korma/where where-map)))))

(defn count-pending-requests
  "Returns the number of requests pending."
  []
  (count-requests { (h2-keyword request-status-key) pending-status }))

(defn count-pending-acceptance-requests
  "Returns the number of requests waiting to be accepted by the user."
  []
  (count-requests
    { (h2-keyword request-status-key) pending-acceptance-status }))

(defn find-table-request
  "Returns a friend request with just the id and profile id at the given index
with the given where map."
  [index where-map]
  (first
    (korma/select
      friend-request
      (korma/fields (h2-keyword :id) (h2-keyword profile-id-key))
      (korma/where where-map)
      (korma/limit 1)
      (korma/offset index))))

(defn pending-request
  "Returns the pending request at the given index."
  [index]
  (find-table-request index { (h2-keyword request-status-key) pending-status }))

(defn pending-acceptance-request
  "Returns the pending acceptance request at the given index."
  [index]
  (find-table-request index
    { (h2-keyword request-status-key) pending-acceptance-status }))