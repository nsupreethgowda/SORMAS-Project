/*******************************************************************************
 * SORMAS® - Surveillance Outbreak Response Management & Analysis System
 * Copyright © 2016-2018 Helmholtz-Zentrum für Infektionsforschung GmbH (HZI)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *******************************************************************************/
package de.symeda.sormas.backend.contact;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import javax.ejb.EJB;
import javax.ejb.LocalBean;
import javax.ejb.Stateless;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.validation.constraints.NotNull;

import de.symeda.sormas.api.Disease;
import de.symeda.sormas.api.EntityRelevanceStatus;
import de.symeda.sormas.api.contact.ContactClassification;
import de.symeda.sormas.api.contact.ContactCriteria;
import de.symeda.sormas.api.contact.ContactProximity;
import de.symeda.sormas.api.contact.ContactReferenceDto;
import de.symeda.sormas.api.contact.ContactStatus;
import de.symeda.sormas.api.contact.DashboardContactDto;
import de.symeda.sormas.api.contact.FollowUpStatus;
import de.symeda.sormas.api.contact.MapContactDto;
import de.symeda.sormas.api.task.TaskCriteria;
import de.symeda.sormas.api.user.UserRole;
import de.symeda.sormas.api.utils.DataHelper;
import de.symeda.sormas.api.utils.DateHelper;
import de.symeda.sormas.api.visit.VisitDto;
import de.symeda.sormas.api.visit.VisitStatus;
import de.symeda.sormas.backend.caze.Case;
import de.symeda.sormas.backend.caze.CaseService;
import de.symeda.sormas.backend.common.AbstractAdoService;
import de.symeda.sormas.backend.common.AbstractCoreAdoService;
import de.symeda.sormas.backend.common.CoreAdo;
import de.symeda.sormas.backend.disease.DiseaseConfigurationFacadeEjb.DiseaseConfigurationFacadeEjbLocal;
import de.symeda.sormas.backend.facility.Facility;
import de.symeda.sormas.backend.location.Location;
import de.symeda.sormas.backend.person.Person;
import de.symeda.sormas.backend.person.PersonFacadeEjb.PersonFacadeEjbLocal;
import de.symeda.sormas.backend.region.District;
import de.symeda.sormas.backend.region.Region;
import de.symeda.sormas.backend.symptoms.Symptoms;
import de.symeda.sormas.backend.task.Task;
import de.symeda.sormas.backend.task.TaskService;
import de.symeda.sormas.backend.user.User;
import de.symeda.sormas.backend.util.DateHelper8;
import de.symeda.sormas.backend.visit.Visit;
import de.symeda.sormas.backend.visit.VisitService;

@Stateless
@LocalBean
public class ContactService extends AbstractCoreAdoService<Contact> {

	@EJB
	CaseService caseService;
	@EJB
	VisitService visitService;
	@EJB
	PersonFacadeEjbLocal personFacade;
	@EJB
	DiseaseConfigurationFacadeEjbLocal diseaseConfigurationFacade;
	@EJB
	TaskService taskService;

	public ContactService() {
		super(Contact.class);
	}

	public List<Contact> findBy(ContactCriteria contactCriteria, User user) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Contact> cq = cb.createQuery(getElementClass());
		Root<Contact> from = cq.from(getElementClass());

		Predicate filter = buildCriteriaFilter(contactCriteria, cb, from);

		if (user != null) {
			filter = and(cb, filter, createUserFilter(cb, cq, from, user));
		}
		if (filter != null) {
			cq.where(filter);
		}
		cq.orderBy(cb.asc(from.get(Contact.CREATION_DATE)));

		List<Contact> resultList = em.createQuery(cq).getResultList();
		return resultList;
	}

	public List<Contact> getAllActiveContactsAfter(Date date, User user) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Contact> cq = cb.createQuery(getElementClass());
		Root<Contact> from = cq.from(getElementClass());

		Predicate filter = createActiveContactsFilter(cb, from);

		if (user != null) {
			Predicate userFilter = createUserFilter(cb, cq, from, user);
			filter = AbstractAdoService.and(cb, filter, userFilter);
		}

		if (date != null) {
			Predicate dateFilter = createChangeDateFilter(cb, from, date);
			filter = AbstractAdoService.and(cb, filter, dateFilter);	
		}

		cq.where(filter);
		cq.orderBy(cb.desc(from.get(Contact.CHANGE_DATE)));
		cq.distinct(true);

		return em.createQuery(cq).getResultList();
	}

	public List<String> getAllActiveUuids(User user) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<String> cq = cb.createQuery(String.class);
		Root<Contact> from = cq.from(getElementClass());

		Predicate filter = createActiveContactsFilter(cb, from);

		if (user != null) {
			Predicate userFilter = createUserFilter(cb, cq, from, user);
			filter = AbstractAdoService.and(cb, filter, userFilter);
		}

		cq.where(filter);
		cq.select(from.get(Contact.UUID));

		return em.createQuery(cq).getResultList();
	}

	public int getContactCountByCase(Case caze) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Long> cq = cb.createQuery(Long.class);
		Root<Contact> from = cq.from(getElementClass());

		cq.select(cb.count(from));
		cq.where(cb.and(
				createDefaultFilter(cb, from),
				cb.equal(from.get(Contact.CAZE), caze)));

		return em.createQuery(cq).getSingleResult().intValue();
	}

	public List<Contact> getAllByResultingCase(Case caze) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Contact> cq = cb.createQuery(getElementClass());
		Root<Contact> from = cq.from(getElementClass());

		cq.where(cb.and(
				createDefaultFilter(cb, from),
				cb.equal(from.get(Contact.RESULTING_CASE), caze)));
		cq.orderBy(cb.desc(from.get(Contact.REPORT_DATE_TIME)));

		List<Contact> resultList = em.createQuery(cq).getResultList();
		return resultList;
	}

	public List<Object[]> getSourceCaseClassifications(List<Long> caseIds) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
		Root<Contact> contactRoot = cq.from(getElementClass());
		Join<Contact, Case> rootCaseJoin = contactRoot.join(Contact.CAZE);
		Join<Contact, Case> resultingCaseJoin = contactRoot.join(Contact.RESULTING_CASE);

		cq.multiselect(
				resultingCaseJoin.get(Case.ID),
				rootCaseJoin.get(Case.CASE_CLASSIFICATION));

		Expression<String> caseIdsExpression = resultingCaseJoin.get(Case.ID);
		cq.where(cb.and(
				caseIdsExpression.in(caseIds),
				cb.isNotNull(contactRoot.get(Contact.CAZE))
				));

		return em.createQuery(cq).getResultList();
	}

	public List<Contact> getFollowUpBetween(@NotNull Date fromDate, @NotNull Date toDate) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Contact> cq = cb.createQuery(getElementClass());
		Root<Contact> from = cq.from(getElementClass());

		Predicate filter = createActiveContactsFilter(cb, from);
		filter = cb.and(filter, cb.isNotNull(from.get(Contact.FOLLOW_UP_UNTIL)));
		filter = cb.and(filter, cb.greaterThanOrEqualTo(from.get(Contact.FOLLOW_UP_UNTIL), fromDate));
		filter = cb.and(filter, cb.lessThan(from.get(Contact.LAST_CONTACT_DATE), toDate));

		cq.where(filter);

		List<Contact> resultList = em.createQuery(cq).getResultList();
		return resultList;
	}

	public List<Contact> getByPersonAndDisease(Person person, Disease disease) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Contact> cq = cb.createQuery(getElementClass());
		Root<Contact> from = cq.from(getElementClass());

		Predicate filter = createDefaultFilter(cb, from);
		filter = cb.and(filter, cb.equal(from.get(Contact.PERSON), person));
		filter = cb.and(filter, cb.equal(from.get(Contact.DISEASE), disease));
		cq.where(filter);

		List<Contact> result = em.createQuery(cq).getResultList();
		return result;
	}

	public List<String> getDeletedUuidsSince(User user, Date since) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<String> cq = cb.createQuery(String.class);
		Root<Contact> contact = cq.from(Contact.class);

		Predicate filter = createUserFilter(cb, cq, contact, user);
		if (since != null) {
			Predicate dateFilter = cb.greaterThanOrEqualTo(contact.get(Contact.CHANGE_DATE), since);
			if (filter != null) {
				filter = cb.and(filter, dateFilter);
			} else {
				filter = dateFilter;
			}
		}

		Predicate deletedFilter = cb.equal(contact.get(Contact.DELETED), true);
		if (filter != null) {
			filter = cb.and(filter, deletedFilter);
		} else {
			filter = deletedFilter;
		}

		cq.where(filter);
		cq.select(contact.get(Contact.UUID));

		return em.createQuery(cq).getResultList();
	}

	public List<MapContactDto> getContactsForMap(Region region, District district, Disease disease, Date fromDate,
			Date toDate, User user, List<String> caseUuids) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<MapContactDto> cq = cb.createQuery(MapContactDto.class);
		Root<Contact> contact = cq.from(getElementClass());
		Join<Contact, Person> person = contact.join(Contact.PERSON, JoinType.LEFT);
		Join<Person, Location> contactPersonAddress = person.join(Person.ADDRESS, JoinType.LEFT);
		Join<Contact, Case> caze = contact.join(Contact.CAZE, JoinType.LEFT);
		Join<Case, Person> casePerson = caze.join(Case.PERSON, JoinType.LEFT);
		Join<Case, Symptoms> symptoms = caze.join(Case.SYMPTOMS, JoinType.LEFT);

		Predicate filter = createActiveContactsFilter(cb, contact);
		filter = AbstractAdoService.and(cb, filter, createUserFilter(cb, cq, contact, user));

		if (caseUuids != null) {
			Path<Object> contactCaseUuid = contact.get(Contact.CAZE).get(Case.UUID);
			Predicate caseFilter = cb.or(
					cb.isNull(contact.get(Contact.CAZE)),
					contactCaseUuid.in(caseUuids)
					);
			if (filter != null) {
				filter = cb.and(filter, caseFilter);
			} else {
				filter = caseFilter;
			}
		}

		if (region != null) {
			Predicate regionFilter = cb.or(
					cb.equal(contact.get(Contact.REGION), region),
					cb.and(
							cb.isNull(contact.get(Contact.REGION)),
							cb.equal(caze.get(Case.REGION), region)
							));
			if (filter != null) {
				filter = cb.and(filter, regionFilter);
			} else {
				filter = regionFilter;
			}
		}

		if (district != null) {
			Predicate districtFilter = cb.or(
					cb.equal(contact.get(Contact.DISTRICT), district),
					cb.and(
							cb.isNull(contact.get(Contact.DISTRICT)),
							cb.equal(caze.get(Case.DISTRICT), district)
							));
			if (filter != null) {
				filter = cb.and(filter, districtFilter);
			} else {
				filter = districtFilter;
			}
		}

		if (disease != null) {
			Predicate diseaseFilter = cb.equal(contact.get(Contact.DISEASE), disease);
			if (filter != null) {
				filter = cb.and(filter, diseaseFilter);
			} else {
				filter = diseaseFilter;
			}
		}

		// Only retrieve contacts that are currently under follow-up
		Predicate followUpFilter = cb.equal(contact.get(Contact.FOLLOW_UP_STATUS), FollowUpStatus.FOLLOW_UP);
		if (filter != null) {
			filter = cb.and(filter, followUpFilter);
		} else {
			filter = followUpFilter;
		}

		List<MapContactDto> result;
		if (filter != null) {
			cq.where(filter);
			cq.multiselect(contact.get(Contact.UUID), contact.get(Contact.CONTACT_CLASSIFICATION),
					contact.get(Contact.REPORT_LAT), contact.get(Contact.REPORT_LON),
					contactPersonAddress.get(Location.LATITUDE), contactPersonAddress.get(Location.LONGITUDE),
					symptoms.get(Symptoms.ONSET_DATE), caze.get(Case.REPORT_DATE), person.get(Person.FIRST_NAME),
					person.get(Person.LAST_NAME), casePerson.get(Person.FIRST_NAME), casePerson.get(Person.LAST_NAME));

			result = em.createQuery(cq).getResultList();
			// #1274 Temporarily disabled because it severely impacts the performance of the Dashboard
			//			for (MapContactDto mapContactDto : result) {
			//				Visit lastVisit = visitService.getLastVisitByContact(getByUuid(mapContactDto.getUuid()),
			//						VisitStatus.COOPERATIVE);
			//				if (lastVisit != null) {
			//					mapContactDto.setLastVisitDateTime(lastVisit.getVisitDateTime());
			//				}
			//			}
		} else {
			result = Collections.emptyList();
		}

		return result;
	}

	public List<DashboardContactDto> getContactsForDashboard(Region region, District district, Disease disease, Date from, Date to, User user) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<DashboardContactDto> cq = cb.createQuery(DashboardContactDto.class);
		Root<Contact> contact = cq.from(getElementClass());
		Join<Contact, Case> caze = contact.join(Contact.CAZE, JoinType.LEFT);

		Predicate filter = createDefaultFilter(cb, contact);
		filter = AbstractAdoService.and(cb, filter, createUserFilter(cb, cq, contact, user));

		// Date filter
		Predicate dateFilter = cb.and(
				cb.lessThanOrEqualTo(contact.get(Contact.REPORT_DATE_TIME), to),
				cb.greaterThanOrEqualTo(contact.get(Contact.FOLLOW_UP_UNTIL), from)
				);
		if (filter != null) {
			filter = cb.and(filter, dateFilter);
		} else {
			filter = dateFilter;
		}

		if (region != null) {
			Predicate regionFilter = cb.or(
					cb.equal(contact.get(Contact.REGION), region),
					cb.and(
							cb.isNull(contact.get(Contact.REGION)),
							cb.equal(caze.get(Case.REGION), region)
							));
			if (filter != null) {
				filter = cb.and(filter, regionFilter);
			} else {
				filter = regionFilter;
			}
		}

		if (district != null) {
			Predicate districtFilter = cb.or(
					cb.equal(contact.get(Contact.DISTRICT), district),
					cb.and(
							cb.isNull(contact.get(Contact.DISTRICT)),
							cb.equal(caze.get(Case.DISTRICT), district)
							));
			if (filter != null) {
				filter = cb.and(filter, districtFilter);
			} else {
				filter = districtFilter;
			}
		}

		if (disease != null) {
			Predicate diseaseFilter = cb.equal(contact.get(Contact.DISEASE), disease);
			if (filter != null) {
				filter = cb.and(filter, diseaseFilter);
			} else {
				filter = diseaseFilter;
			}
		}

		List<DashboardContactDto> result;
		if (filter != null) {
			cq.where(filter);
			cq.multiselect(
					contact.get(Contact.UUID),
					contact.get(Contact.REPORT_DATE_TIME),
					contact.get(Contact.CONTACT_STATUS),
					contact.get(Contact.CONTACT_CLASSIFICATION),
					contact.get(Contact.FOLLOW_UP_STATUS),
					contact.get(Contact.FOLLOW_UP_UNTIL),
					contact.get(Contact.DISEASE));

			result = em.createQuery(cq).getResultList();	
			for (DashboardContactDto dashboardContactDto : result) {
				Visit lastVisit = visitService.getLastVisitByContact(getByUuid(dashboardContactDto.getUuid()), null);
				dashboardContactDto.setSymptomatic(lastVisit != null ? lastVisit.getSymptoms().getSymptomatic() : false);
				dashboardContactDto.setLastVisitStatus(lastVisit != null ? lastVisit.getVisitStatus() : null);
				dashboardContactDto.setLastVisitDateTime(lastVisit != null ? lastVisit.getVisitDateTime() : null);
			}
		} else {
			result = Collections.emptyList();
		}

		return result;
	}

	public Map<ContactStatus, Long> getNewContactCountPerStatus(ContactCriteria contactCriteria, User user) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
		Root<Contact> contact = cq.from(getElementClass());

		Predicate filter = createUserFilter(cb, cq, contact, user);
		Predicate criteriaFilter = buildCriteriaFilter(contactCriteria, cb, contact);
		if (filter != null) {
			filter = cb.and(filter, criteriaFilter);
		} else {
			filter = criteriaFilter;
		}

		if (filter != null) {
			cq.where(filter);
		}

		cq.groupBy(contact.get(Contact.CONTACT_STATUS));
		cq.multiselect(contact.get(Contact.CONTACT_STATUS), cb.count(contact));
		List<Object[]> results = em.createQuery(cq).getResultList();

		Map<ContactStatus, Long> resultMap = results.stream().collect(
				Collectors.toMap(e -> (ContactStatus) e[0], e -> (Long) e[1]));
		return resultMap;
	}

	public Map<ContactClassification, Long> getNewContactCountPerClassification(ContactCriteria contactCriteria, User user) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
		Root<Contact> contact = cq.from(getElementClass());

		Predicate filter = createUserFilter(cb, cq, contact, user);
		Predicate criteriaFilter = buildCriteriaFilter(contactCriteria, cb, contact);
		if (filter != null) {
			filter = cb.and(filter, criteriaFilter);
		} else {
			filter = criteriaFilter;
		}

		if (filter != null) {
			cq.where(filter);
		}

		cq.groupBy(contact.get(Contact.CONTACT_CLASSIFICATION));
		cq.multiselect(contact.get(Contact.CONTACT_CLASSIFICATION), cb.count(contact));
		List<Object[]> results = em.createQuery(cq).getResultList();

		Map<ContactClassification, Long> resultMap = results.stream().collect(
				Collectors.toMap(e -> (ContactClassification) e[0], e -> (Long) e[1]));
		return resultMap;
	}

	public Map<FollowUpStatus, Long> getNewContactCountPerFollowUpStatus(ContactCriteria contactCriteria, User user) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
		Root<Contact> contact = cq.from(getElementClass());

		Predicate filter = createUserFilter(cb, cq, contact, user);
		Predicate criteriaFilter = buildCriteriaFilter(contactCriteria, cb, contact);
		if (filter != null) {
			filter = cb.and(filter, criteriaFilter);
		} else {
			filter = criteriaFilter;
		}

		if (filter != null) {
			cq.where(filter);
		}

		cq.groupBy(contact.get(Contact.FOLLOW_UP_STATUS));
		cq.multiselect(contact.get(Contact.FOLLOW_UP_STATUS), cb.count(contact));
		List<Object[]> results = em.createQuery(cq).getResultList();

		Map<FollowUpStatus, Long> resultMap = results.stream().collect(
				Collectors.toMap(e -> (FollowUpStatus) e[0], e -> (Long) e[1]));
		return resultMap;
	}

	public int getFollowUpUntilCount(ContactCriteria contactCriteria, User user) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Long> cq = cb.createQuery(Long.class);
		Root<Contact> contact = cq.from(getElementClass());

		Predicate filter = createUserFilter(cb, cq, contact, user);
		Predicate criteriaFilter = buildCriteriaFilter(contactCriteria, cb, contact);
		if (filter != null) {
			filter = cb.and(filter, criteriaFilter);
		} else {
			filter = criteriaFilter;
		}

		cq.select(cb.count(contact));

		if (filter != null) {
			cq.where(filter);
		}

		return em.createQuery(cq).getSingleResult().intValue();
	}

	/**
	 * Calculates resultingCase and contact status based on: - existing disease
	 * cases (and classification) of the person - the incubation period and - the
	 * contact classification - the follow-up status
	 */
	public void udpateContactStatus(Contact contact) {
		ContactClassification contactClassification = contact.getContactClassification();
		if (contactClassification == null) { // Fall-back
			contactClassification = ContactClassification.UNCONFIRMED;
		}

		switch (contactClassification) {
		case UNCONFIRMED:
			contact.setContactStatus(ContactStatus.ACTIVE);
			break;
		case NO_CONTACT:
			contact.setContactStatus(ContactStatus.DROPPED);
			break;
		case CONFIRMED:
			if (contact.getResultingCase() != null) {
				contact.setContactStatus(ContactStatus.CONVERTED);
			} else {
				if (contact.getFollowUpStatus() != null) {
					switch (contact.getFollowUpStatus()) {
					case CANCELED:
					case COMPLETED:
					case LOST:
					case NO_FOLLOW_UP:
						contact.setContactStatus(ContactStatus.DROPPED);
						break;
					case FOLLOW_UP:
						contact.setContactStatus(ContactStatus.ACTIVE);
						break;
					default:
						throw new NoSuchElementException(contact.getFollowUpStatus().toString());
					}
				} else {
					contact.setContactStatus(ContactStatus.ACTIVE);
				}
			}
			break;
		default:
			throw new NoSuchElementException(DataHelper.toStringNullable(contactClassification));
		}

		ensurePersisted(contact);
	}

	/**
	 * Calculates and sets the follow-up until date and status of the contact.
	 * <ul>
	 * <li>Disease with no follow-up: Leave empty and set follow-up status to "No
	 * follow-up"</li>
	 * <li>Others: Use follow-up duration of the disease. Reference for calculation
	 * is the reporting date (since this is always later than the last contact date
	 * and we can't be sure the last contact date is correct) If the last visit was
	 * not cooperative and happened at the last date of contact tracing, we need to
	 * do an additional visit.</li>
	 * </ul>
	 */
	public void updateFollowUpUntilAndStatus(Contact contact) {
		Disease disease = contact.getDisease();
		boolean changeStatus = contact.getFollowUpStatus() != FollowUpStatus.CANCELED
				&& contact.getFollowUpStatus() != FollowUpStatus.LOST;

		ContactProximity contactProximity = contact.getContactProximity();
		if (!diseaseConfigurationFacade.hasFollowUp(disease)
				|| (contactProximity != null && !contactProximity.hasFollowUp())) {
			contact.setFollowUpUntil(null);
			if (changeStatus) {
				contact.setFollowUpStatus(FollowUpStatus.NO_FOLLOW_UP);
			}
		} else {

			int followUpDuration = diseaseConfigurationFacade.getFollowUpDuration(disease);
			LocalDate beginDate = DateHelper8.toLocalDate(contact.getReportDateTime());
			LocalDate untilDate = beginDate.plusDays(followUpDuration);

			Visit lastVisit = null;
			boolean additionalVisitNeeded;
			do {
				additionalVisitNeeded = false;
				lastVisit = visitService.getLastVisitByPerson(contact.getPerson(), disease, untilDate);
				if (lastVisit != null) {
					// if the last visit was not cooperative and happened at the last date of
					// contact tracing ..
					if (lastVisit.getVisitStatus() != VisitStatus.COOPERATIVE
							&& DateHelper8.toLocalDate(lastVisit.getVisitDateTime()).isEqual(untilDate)) {
						// .. we need to do an additional visit
						additionalVisitNeeded = true;
						untilDate = untilDate.plusDays(1);
					}
				}
			} while (additionalVisitNeeded);

			contact.setFollowUpUntil(DateHelper8.toDate(untilDate));
			if (changeStatus) {
				// completed or still follow up?
				if (lastVisit != null && DateHelper8.toLocalDate(lastVisit.getVisitDateTime()).isEqual(untilDate)) {
					contact.setFollowUpStatus(FollowUpStatus.COMPLETED);
				} else {
					contact.setFollowUpStatus(FollowUpStatus.FOLLOW_UP);
				}
			}
		}

		ensurePersisted(contact);
	}

	public void updateFollowUpUntilAndStatusByVisit(Visit visit) {
		List<Contact> contacts = getByPersonAndDisease(visit.getPerson(), visit.getDisease());
		for (Contact contact : contacts) {
			updateFollowUpUntilAndStatus(contact);
		}
	}

	public List<Contact> getAllByVisit(Visit visit) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Contact> cq = cb.createQuery(getElementClass());
		Root<Contact> from = cq.from(getElementClass());

		Predicate filter = createActiveContactsFilter(cb, from);
		filter = cb.and(filter, cb.equal(from.get(Contact.PERSON), visit.getPerson()));
		filter = cb.and(filter, cb.equal(from.get(Contact.DISEASE), visit.getDisease()));

		Predicate dateStartFilter = cb.or(
				cb.and(cb.isNotNull(from.get(Contact.LAST_CONTACT_DATE)),
						cb.lessThan(from.get(Contact.LAST_CONTACT_DATE),
								DateHelper.addDays(visit.getVisitDateTime(), VisitDto.ALLOWED_CONTACT_DATE_OFFSET))),
				cb.lessThan(from.get(Contact.REPORT_DATE_TIME),
						DateHelper.addDays(visit.getVisitDateTime(), VisitDto.ALLOWED_CONTACT_DATE_OFFSET)));

		Predicate dateEndFilter = cb.greaterThan(from.get(Contact.FOLLOW_UP_UNTIL),
				DateHelper.subtractDays(visit.getVisitDateTime(), VisitDto.ALLOWED_CONTACT_DATE_OFFSET));

		filter = cb.and(filter, dateStartFilter);
		filter = cb.and(filter, dateEndFilter);

		cq.where(filter);

		List<Contact> resultList = em.createQuery(cq).getResultList();
		return resultList;
	}

	/**
	 * @see /sormas-backend/doc/UserDataAccess.md
	 */
	@SuppressWarnings("rawtypes")
	@Override
	public Predicate createUserFilter(CriteriaBuilder cb, CriteriaQuery cq, From<Contact, Contact> contactPath,
			User user) {
		Predicate userFilter = caseService.createUserFilter(cb, cq, contactPath.join(Contact.CAZE, JoinType.LEFT),
				user);
		Predicate filter;
		if (userFilter != null) {
			filter = cb.or(createUserFilterWithoutCase(cb, cq, contactPath, user), userFilter);
		} else {
			filter = createUserFilterWithoutCase(cb, cq, contactPath, user);
		}
		return filter;
	}

	@SuppressWarnings("rawtypes")
	public Predicate createUserFilterWithoutCase(CriteriaBuilder cb, CriteriaQuery cq,
			From<Contact, Contact> contactPath, User user) {
		// National users can access all contacts in the system
		if (user.getUserRoles().contains(UserRole.NATIONAL_USER)
				|| user.getUserRoles().contains(UserRole.NATIONAL_CLINICIAN)
				|| user.getUserRoles().contains(UserRole.NATIONAL_OBSERVER)) {
			if (user.getLimitedDisease() != null) {
				return cb.equal(contactPath.get(Contact.DISEASE), user.getLimitedDisease());
			} else {
				return null;
			}
		}

		// whoever created it or is assigned to it is allowed to access it
		Predicate filter = cb.equal(contactPath.join(Contact.REPORTING_USER, JoinType.LEFT), user);
		filter = cb.or(filter, cb.equal(contactPath.join(Contact.CONTACT_OFFICER, JoinType.LEFT), user));

		// users have access to all contacts in their region/district
		if (user.getDistrict() != null) {
			filter = cb.or(filter, cb.equal(contactPath.get(Contact.DISTRICT), user.getDistrict()));
		} else if (user.getRegion() != null) {
			filter = cb.or(filter, cb.equal(contactPath.get(Contact.REGION), user.getRegion()));
		}

		return filter;
	}

	public Predicate buildCriteriaFilter(ContactCriteria contactCriteria, CriteriaBuilder cb, Root<Contact> from) {
		Predicate filter = null;
		Join<Contact, Case> caze = from.join(Contact.CAZE, JoinType.LEFT);

		if (contactCriteria.getReportingUserRole() != null) {
			filter = and(cb, filter, cb.isMember(contactCriteria.getReportingUserRole(),
					from.join(Contact.REPORTING_USER, JoinType.LEFT).get(User.USER_ROLES)));
		}
		if (contactCriteria.getDisease() != null) {
			filter = and(cb, filter, cb.equal(from.get(Contact.DISEASE), contactCriteria.getDisease()));
		}
		if (contactCriteria.getCaze() != null) {
			filter = and(cb, filter, cb.equal(caze.get(Case.UUID), contactCriteria.getCaze().getUuid()));
		}
		if (contactCriteria.getRegion() != null) {
			filter = and(cb, filter, cb.or(
					cb.equal(from.join(Contact.REGION, JoinType.LEFT).get(Region.UUID), contactCriteria.getRegion().getUuid()),
					cb.and(
							cb.isNull(from.get(Contact.REGION)),
							cb.equal(caze.join(Case.REGION, JoinType.LEFT).get(Region.UUID), contactCriteria.getRegion().getUuid())
					)));
		}
		if (contactCriteria.getDistrict() != null) {
			filter = and(cb, filter, cb.or(
					cb.equal(from.join(Contact.DISTRICT, JoinType.LEFT).get(District.UUID), contactCriteria.getDistrict().getUuid()),
					cb.and(
							cb.isNull(from.get(Contact.DISTRICT)),
							cb.equal(caze.join(Case.DISTRICT, JoinType.LEFT).get(District.UUID), contactCriteria.getDistrict().getUuid())
					)));
		}
		if (contactCriteria.getCaseFacility() != null) {
			filter = and(cb, filter, cb.equal(caze.join(Case.HEALTH_FACILITY, JoinType.LEFT).get(Facility.UUID),
					contactCriteria.getCaseFacility().getUuid()));
		}
		if (contactCriteria.getContactOfficer() != null) {
			filter = and(cb, filter, cb.equal(from.join(Contact.CONTACT_OFFICER, JoinType.LEFT).get(User.UUID),
					contactCriteria.getContactOfficer().getUuid()));
		}
		if (contactCriteria.getContactClassification() != null) {
			filter = and(cb, filter,
					cb.equal(from.get(Contact.CONTACT_CLASSIFICATION), contactCriteria.getContactClassification()));
		}
		if (contactCriteria.getContactStatus() != null) {
			filter = and(cb, filter, cb.equal(from.get(Contact.CONTACT_STATUS), contactCriteria.getContactStatus()));
		}
		if (contactCriteria.getFollowUpStatus() != null) {
			filter = and(cb, filter, cb.equal(from.get(Contact.FOLLOW_UP_STATUS), contactCriteria.getFollowUpStatus()));
		}
		if (contactCriteria.getReportDateFrom() != null && contactCriteria.getReportDateTo() != null) {
			filter = and(cb, filter, cb.between(from.get(Contact.REPORT_DATE_TIME), contactCriteria.getReportDateFrom(), contactCriteria.getReportDateTo()));
		} else if (contactCriteria.getReportDateFrom() != null) {
			filter = and(cb, filter, cb.greaterThanOrEqualTo(from.get(Contact.REPORT_DATE_TIME), contactCriteria.getReportDateFrom()));
		} else if (contactCriteria.getReportDateTo() != null) {
			filter = and(cb, filter, cb.lessThanOrEqualTo(from.get(Contact.REPORT_DATE_TIME), contactCriteria.getReportDateTo()));
		}
		if (contactCriteria.getLastContactDateFrom() != null && contactCriteria.getLastContactDateTo() != null) {
			filter = and(cb, filter, cb.between(from.get(Contact.LAST_CONTACT_DATE), contactCriteria.getLastContactDateFrom(), contactCriteria.getLastContactDateTo()));
		}
		if (contactCriteria.getFollowUpUntilFrom() != null && contactCriteria.getFollowUpUntilTo() != null) {
			filter = and(cb, filter, cb.between(from.get(Contact.FOLLOW_UP_UNTIL), contactCriteria.getFollowUpUntilFrom(), contactCriteria.getFollowUpUntilTo()));
		} else if (contactCriteria.getFollowUpUntilFrom() != null) {
			filter = and(cb, filter, cb.greaterThanOrEqualTo(from.get(Contact.FOLLOW_UP_UNTIL), contactCriteria.getFollowUpUntilFrom()));
		} else if (contactCriteria.getFollowUpUntilTo() != null) {
			filter = and(cb, filter, cb.lessThanOrEqualTo(from.get(Contact.FOLLOW_UP_UNTIL), contactCriteria.getFollowUpUntilTo()));
		}
		if (contactCriteria.getRelevanceStatus() != null) {
			if (contactCriteria.getRelevanceStatus() == EntityRelevanceStatus.ACTIVE) {
				filter = and(cb, filter, cb.or(
						cb.equal(caze.get(Case.ARCHIVED), false),
						cb.isNull(caze.get(Case.ARCHIVED))));
			} else if (contactCriteria.getRelevanceStatus() == EntityRelevanceStatus.ARCHIVED) {
				filter = and(cb, filter, cb.equal(caze.get(Case.ARCHIVED), true));
			}
		}
		if (contactCriteria.getDeleted() != null) {
			filter = and(cb, filter, cb.equal(from.get(Case.DELETED), contactCriteria.getDeleted()));
		}
		if (contactCriteria.getNameUuidCaseLike() != null) {
			Join<Contact, Person> person = from.join(Contact.PERSON, JoinType.LEFT);
			Join<Case, Person> casePerson = caze.join(Case.PERSON, JoinType.LEFT);
			String[] textFilters = contactCriteria.getNameUuidCaseLike().split("\\s+");
			for (int i=0; i<textFilters.length; i++)
			{
				String textFilter = "%" + textFilters[i].toLowerCase() + "%";
				if (!DataHelper.isNullOrEmpty(textFilter)) {
					Predicate likeFilters = cb.or(
							cb.like(cb.lower(from.get(Contact.UUID)), textFilter),
							cb.like(cb.lower(person.get(Person.FIRST_NAME)), textFilter),
							cb.like(cb.lower(person.get(Person.LAST_NAME)), textFilter),
							cb.like(cb.lower(caze.get(Case.UUID)), textFilter),
							cb.like(cb.lower(casePerson.get(Person.FIRST_NAME)), textFilter),
							cb.like(cb.lower(casePerson.get(Person.LAST_NAME)), textFilter));
					filter = and(cb, filter, likeFilters);
				}
			}
		}
		if (Boolean.TRUE.equals(contactCriteria.getOnlyHighPriorityContacts())) {
			filter = and(cb, filter, cb.equal(from.get(Contact.HIGH_PRIORITY), true));
		}

		return filter;
	}

	@Override
	public void delete(Contact contact) {
		// TODO: Visits can not be deleted here yet because they are not directly associated with a contact
		// and could potentially be relevant for another contact as well. This logic needs to be revamped.
		//		List<Visit> visits = visitService.getAllByContact(contact);
		//		for (Visit visit : visits) {
		//			visitService.delete(visit);
		//		}

		// Delete all tasks associated with this contact
		List<Task> tasks = taskService.findBy(new TaskCriteria().contact(new ContactReferenceDto(contact.getUuid())));
		for (Task task : tasks) {
			taskService.delete(task);
		}

		super.delete(contact);
	}

	/**
	 * Creates a filter that excludes all contacts that are either {@link CoreAdo#deleted} or associated with
	 * cases that are {@link Case#archived}.
	 */
	public Predicate createActiveContactsFilter(CriteriaBuilder cb, Root<Contact> root) {
		Join<Contact, Case> caze = root.join(Contact.CAZE, JoinType.LEFT);
		return cb.and(
				cb.or(
						cb.isNull(root.get(Contact.CAZE)),
						cb.isFalse(caze.get(Case.ARCHIVED))
						),
				cb.isFalse(root.get(Contact.DELETED)));
	}

	/**
	 * Creates a default filter that should be used as the basis of queries that do not use {@link ContactCriteria}.
	 * This essentially removes {@link CoreAdo#deleted} contacts from the queries.
	 */
	public Predicate createDefaultFilter(CriteriaBuilder cb, Root<Contact> root) {
		return cb.isFalse(root.get(Contact.DELETED));
	}

}
