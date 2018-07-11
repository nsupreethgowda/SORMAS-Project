package de.symeda.sormas.app.caze.read;

import android.os.Bundle;
import android.view.View;

import de.symeda.sormas.api.I18nProperties;
import de.symeda.sormas.api.caze.CaseDataDto;
import de.symeda.sormas.api.caze.Vaccination;
import de.symeda.sormas.api.facility.FacilityDto;
import de.symeda.sormas.api.facility.FacilityHelper;
import de.symeda.sormas.api.utils.YesNoUnknown;
import de.symeda.sormas.app.BaseReadFragment;
import de.symeda.sormas.app.R;
import de.symeda.sormas.app.backend.caze.Case;
import de.symeda.sormas.app.backend.facility.Facility;
import de.symeda.sormas.app.component.controls.ControlPropertyField;
import de.symeda.sormas.app.component.controls.ControlTextReadField;
import de.symeda.sormas.app.component.controls.ValueChangeListener;
import de.symeda.sormas.app.databinding.FragmentCaseReadLayoutBinding;
import de.symeda.sormas.app.shared.CaseFormNavigationCapsule;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

public class CaseReadFragment extends BaseReadFragment<FragmentCaseReadLayoutBinding, Case, Case> {

    private Case record;

    @Override
    protected void prepareFragmentData(Bundle savedInstanceState) {
        record = getActivityRootData();
    }

    @Override
    public void onLayoutBinding(FragmentCaseReadLayoutBinding contentBinding) {
        contentBinding.setData(record);
    }

    @Override
    public void onAfterLayoutBinding(FragmentCaseReadLayoutBinding contentBinding) {
        setUpFieldVisibilities(contentBinding);
    }

    @Override
    protected String getSubHeadingTitle() {
        return getResources().getString(R.string.caption_case_information);
    }

    @Override
    public Case getPrimaryData() {
        return record;
    }

    @Override
    public int getReadLayout() {
        return R.layout.fragment_case_read_layout;
    }

    public static CaseReadFragment newInstance(CaseFormNavigationCapsule capsule, Case activityRootData) {
        return newInstance(CaseReadFragment.class, capsule, activityRootData);
    }

    private void setUpFieldVisibilities(FragmentCaseReadLayoutBinding contentBinding) {
        setVisibilityByDisease(CaseDataDto.class, contentBinding.getData().getDisease(), contentBinding.mainContent);

        // Hide facilityDetails when no static health facility is selected and adjust the caption based on
        // the selected static health facility
        setHealthFacilityDetailsFieldVisibility(contentBinding.caseDataHealthFacility, contentBinding.caseDataHealthFacilityDetails);

        // Vaccination date
        if (isVisibleAllowed(CaseDataDto.class, contentBinding.getData().getDisease(), contentBinding.caseDataVaccination)) {
            setVisibleWhen(contentBinding.caseDataVaccinationDate, contentBinding.caseDataVaccination, Vaccination.VACCINATED);
        }
        if (isVisibleAllowed(CaseDataDto.class, contentBinding.getData().getDisease(), contentBinding.caseDataSmallpoxVaccinationReceived)) {
            setVisibleWhen(contentBinding.caseDataVaccinationDate, contentBinding.caseDataSmallpoxVaccinationReceived, YesNoUnknown.YES);
        }
    }

}