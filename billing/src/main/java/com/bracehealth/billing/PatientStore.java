package com.bracehealth.billing;

import java.util.HashMap;
import java.util.Map;

import com.bracehealth.shared.Patient;
import com.google.common.collect.ImmutableSet;

/** Keeps track of all patients known to the system. */
public class PatientStore {
    private final Map<PatientId, Patient> patients;

    public PatientStore() {
        this.patients = new HashMap<>();
    }

    /**
     * True if the patient was added, false if the patient already existed.
     */
    public synchronized boolean addPatient(Patient patient) {
        PatientId id = PatientId.from(patient);
        if (patients.containsKey(id)) {
            return false;
        }
        patients.put(id, patient);
        return true;
    }

    public ImmutableSet<PatientId> getAllPatientIds() {
        return ImmutableSet.copyOf(patients.keySet());
    }

    public Patient getCanonicalPatient(Patient patient) {
        PatientId id = PatientId.from(patient);
        return patients.get(id);
    }

    public Patient getPatient(PatientId id) {
        return patients.get(id);
    }

    public boolean containsPatient(PatientId id) {
        return patients.containsKey(id);
    }

    public boolean containsPatient(Patient patient) {
        return patients.containsKey(PatientId.from(patient));
    }

    public record PatientId(String firstName, String lastName, String dob) {
        static PatientId from(Patient patient) {
            return new PatientId(patient.getFirstName().toLowerCase(),
                    patient.getLastName().toLowerCase(), patient.getDob());
        }

        static PatientId parse(String id) {
            String[] parts = id.split("_");
            return new PatientId(parts[0], parts[1], parts[2]);
        }
    }

}

