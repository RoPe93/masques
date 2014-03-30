(ns masques.model.profile
  (:require [clj-crypto.core :as clj-crypto]
            [clj-i2p.core :as clj-i2p]
            [masques.edn :as edn]
            [clojure.java.io :as io]
            [config.db-config :as db-config]
            [masques.model.avatar :as avatar-model])
  (:use masques.model.base
        korma.core)
  (:import [org.apache.commons.codec.binary Base64]
           [java.io PushbackReader]))

(def current-user-id 1)

(def alias-key :alias)
(def avatar-path-key :avatar-path)
(def destination-key :destination)
(def identity-key :identity)
(def identity-algorithm-key :identity-algorithm)
(def private-key-key :private-key)
(def private-key-algorithm-key :private-key-algorithm)

(def saved-current-user (atom nil))

(defn find-profile
  "Finds the profile with the given id."
  [id]
  (find-by-id profile id))

(defn delete-profile
  "Deletes the given profile from the database. The profile should include the
id."
  [profile-record]
  (delete-record profile profile-record))

; CURRENT USER

(defn current-user
  "Returns the currently logged in user or nil if no user is logged in."
  []
  @saved-current-user)
  
(defn set-current-user
  "Sets the currently logged in user."
  [profile]
  (reset! saved-current-user profile))
  
; SAVE PROFILE

(defn name-avatar [profile-record]
  (str (alias-key profile-record) "'s Avatar"))

(defn insert-avatar [profile-record]
  (let [avatar-file-map { :path (avatar-path-key profile-record) :name (name-avatar profile-record) }]
    (avatar-model/create-avatar-image (avatar-path-key profile-record))
    (insert-or-update file avatar-file-map)))

(defn save-avatar [profile-record]
  (if (avatar-path-key profile-record)
    (merge profile-record { :avatar-file-id (:id (insert-avatar profile-record)) })
    profile-record))

(defn save [record]
  (insert-or-update profile (dissoc (save-avatar record) :avatar avatar-path-key)))

(defn save-current-user [record]
  (when-not (find-by-id profile current-user-id)
    (save record)))

; BUILD PROFILE

(defn attach-avatar [profile-record]
  (if (:avatar-file-id profile-record)
    (conj { :avatar (find-by-id file (:avatar-file-id profile-record)) } profile-record)
    profile-record))

(defn build [id]
  (attach-avatar (find-by-id profile id)))

; CREATE USER

(defn generate-keys [profile-record]
  (let [key-pair (clj-crypto/generate-key-pair)
        key-pair-map (clj-crypto/get-key-pair-map key-pair)]
    (merge profile-record
      { :id current-user-id
        identity-key (Base64/encodeBase64String
                       (:bytes (:public-key key-pair-map)))
        identity-algorithm-key (:algorithm (:public-key key-pair-map))
        private-key-key (Base64/encodeBase64String
                          (:bytes (:private-key key-pair-map)))
        private-key-algorithm-key (:algorithm (:private-key key-pair-map)) })))

(defn create-user [user-name]
  (insert-record profile (generate-keys { alias-key user-name })))
  
(defn create-friend-profile
  "Creates a profile for a friend where you only have the alias, identity and identity algorithm."
  [alias identity identity-algorithm]
  (save { alias-key alias identity-key identity identity-algorithm-key identity-algorithm }))

(defn find-logged-in-user
  "Finds the profile for the given user name which is a user of this database."
  [user-name]
  (when user-name
    (build (:id (first
      (select profile
        (fields :ID)
        (where { :ALIAS user-name  :PRIVATE_KEY [not= nil]})
        (limit 1)))))))

(defn reload-current-user
  "Reloads the current user from the database. Returns the current user."
  []
  (find-profile current-user-id))

(defn init
  "Loads the currently logged in user's profile into memory. Creating the profile if it does not alreay exist."
  []
  (if-let [logged-in-profile (find-profile current-user-id)]
    (set-current-user logged-in-profile)
    (let [user-name (db-config/current-username)]
      (if-let [new-profile (create-user user-name)]
        (set-current-user new-profile)
        (throw (RuntimeException. (str "Could not create user: "
                                       user-name)))))))

(defn logout
  "Logs out the currently logged in user. Just used for testing."
  []
  (set-current-user nil))

(defn create-masques-id-map
  "Creates a masques id map from the given profile. If the profile is not given,
then the current logged in profile is used."
  ([] (create-masques-id-map (current-user)))
  ([profile]
    (let [destination-map (if (clj-i2p/base-64-destination)
                            { destination-key (clj-i2p/base-64-destination) }
                            {})]
      (merge
        (select-keys profile [alias-key identity-key identity-algorithm-key])
        destination-map))))
           
(defn create-masques-id-file
  "Given a file and a profile, this function saves the profile as a masques id
to the file. If a profile is not given, then the currently logged in profile is
used."
  ([file] (create-masques-id-file file (current-user)))
  ([file profile]
    (edn/write file (create-masques-id-map profile))))

(defn read-masques-id-file 
  "Reads the given masques id file and returns the masques id map."
  [file]
  (edn/read file))

(defn load-masques-id-map
  "Creates a profile from the given masques id map, saves it to the database,
and returns the new id. This function should not be directly called."
  [masques-id-map]
  (when masques-id-map
    (save masques-id-map)))

(defn load-masques-id-file
  "Creates a profile from the given masques id file, saves it to the database
and returns the new id. Do not call this function directly. Use the
send-request in friend_request instead."
  [file]
  (load-masques-id-map (read-masques-id-file file)))

(defn alias
  "Returns the alias for the given profile. If an integer is passed for the
profile, then it is used as the id of the profile to get."
  [profile]
  (if (integer? profile)
    (alias (find-profile profile))
    (alias-key profile)))

(defn identity
  "Returns the identity for the given profile."
  [profile]
  (identity-key profile))

(defn identity-algorithm
  "Returns the identity algorithm used for the given profile."
  [profile]
  (identity-algorithm-key profile))

(defn destination
  "Returns the destination attached to the given profile."
  [profile]
  (destination-key profile))