package org.bowlerframework.squeryl

import org.squeryl.{KeyedEntity}
import org.bowlerframework.view.Renderable
import com.recursivity.commons.bean.{TransformerRegistry, BeanUtils}
import org.bowlerframework.{Response, Request}
import com.recursivity.commons.validator.Equals
import org.bowlerframework.model._

/**
 * Squeryl based CRUD Controller that allows for the following actions:
 * <ul>
 *      <li>GET /[resource]/: gets the first n elements of type T (n defaults to 10)</li>
 *      <li>GET /[resource]/?itemsInList=n: gets the n first elements of type T, changes n default to provided parameter</li>
 *      <li>GET /[resource]/page/2: get's second page of paginated list of elements as above</li>
 *      <li>GET /[resource]/:id : displays the T with the id provided</li>
 *      <li>GET /[resource]/new : should return a create form that POSTs a new T to /</li>
 *      <li>GET /[resource]/:id/edit : should return an edit form that POSTs an updated T to /:id</li>
 *      <li>DELETE /[resource]/:id : Deletes the T with the provided id</li>
 *      <li>POST /[resource]/: Creates a new entity of type T and renders it</li>
 *      <li>POST /[resource]/:id: Updates an entity of type T with the provided id and renders it</li>
 * </ul>
 * For update and create, unique validation and validation of the id is automatically done.
 * Further default validations for T should be registered with the DefaultValidationRegistry.
 */

class CrudController[T <: KeyedEntity[K], K](dao: SquerylDao[T, K], resourceName: String)
                                            (implicit m: scala.Predef.Manifest[T]) extends SquerylController with Validations with ParameterMapper with Renderable {
  val transformer = new SquerylTransformer[T, K](dao)
  TransformerRegistry.registerSingletonTransformer(dao.entityType, transformer)

  get("/" + resourceName + "/")((req, resp) => {
    listResources(1, req, resp)
  })
  get("/" + resourceName)((req, resp) => {
    listResources(1, req, resp)
  })
  get("/" + resourceName + "/page/:number")((req, resp) => {
    listResources(req.getIntParameter("number"), req, resp)
  })

  get("/" + resourceName + "/new")((req, resp) => render(BeanUtils.instantiate[T](dao.entityType)))
  get("/" + resourceName + "/:id")((req, resp) => renderBean(req, resp))
  get("/" + resourceName + "/:id/edit")((req, resp) => renderBean(req, resp))

  delete("/" + resourceName + "/:id")((request, response) => {
    this.mapRequest[T](request)(bean => {
      dao.delete(bean)
      response.setStatus(204)
    })
  })


  // HTTP POST for creating new Widgets.
  post("/" + resourceName + "/")((request, response) => {
    this.mapRequest[T](request)(bean => {
      validate(bean){
        var validator: ModelValidator = new DefaultModelValidator(dao.entityType)
        if(DefaultValidationRegistry.getValidatorBuilder(dao.entityType) != None){
          validator = DefaultValidationRegistry.getValidatorBuilder(dao.entityType).get
          validator.asInstanceOf[ModelValidatorBuilder[T]].initialize(bean)
        }
        validator.add(new SquerylUniqueValidator[T, K]("id", dao, {bean.id}))
        validator.validate
      }
      dao.create(bean)
      render(bean)
    })
  })



  // similar to the above POST, but for updating existing widgets.
  post("/" + resourceName + "/:id")((request, response) => {
    this.mapRequest[T](request)(bean => {
      val id = parseId(request, "id")
      validate(bean) {
        var validator: ModelValidator = new DefaultModelValidator(dao.entityType)
        if(DefaultValidationRegistry.getValidatorBuilder(dao.entityType) != None){
          validator = DefaultValidationRegistry.getValidatorBuilder(dao.entityType).get
          validator.asInstanceOf[ModelValidatorBuilder[T]].initialize(bean)
        }
        validator.add(Equals("id", {id}, {bean.id}))
        validator.validate
      }
      dao.update(bean)
      render(bean)
    })
  })


  def renderBean(request: Request, response: Response) {
    this.mapRequest[Option[T]](request)(bean => {
      render(bean)
    })
  }


  def parseId(request: Request, idName: String): K = {
    return TransformerRegistry.resolveTransformer(dao.keyType).
      getOrElse(throw new IllegalArgumentException("no StringValueTransformer registered for type " + dao.keyType.getName)).toValue(request.getStringParameter(idName)).asInstanceOf[K]
  }

  def listResources(page: Int, request: Request, response: Response) = {
    try {
      request.getSession.setAttribute("_bowlerListItems", request.getIntParameter("itemsInList"))
    } catch {
      case e: Exception => {} // do nothing, fallback to default behavior
    }

    val items = request.getSession.getAttribute[Int]("_bowlerListItems")
    // add check for request param of "itemsInList"
    if (items == None)
      request.getSession.setAttribute("_bowlerListItems", 10)
    val offset = (page - 1) * items.getOrElse(10)
    val maxItems = items.getOrElse(10)

    render(dao.findAll(offset, maxItems))
  }

}