;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.utils.uuid-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.utils.uuid :refer [valid-uuid?]]))

(deftest valid-uuid-test
  (testing "canonical uuids"
    (is (true? (valid-uuid? "123e4567-e89b-12d3-a456-426614174000")))
    (is (true? (valid-uuid? "123E4567-E89B-12D3-A456-426614174000"))))

  (testing "strings that are not uuids"
    (is (false? (valid-uuid? "")))
    (is (false? (valid-uuid? "not-a-uuid")))
    (is (false? (valid-uuid? "123e4567-e89b-12d3-a456-42661417400g")))
    (is (false? (valid-uuid? "123e4567e89b12d3a456426614174000"))))

  (testing "nil is not handled (NullPointerException)"
    (is (thrown? NullPointerException (valid-uuid? nil)))))
